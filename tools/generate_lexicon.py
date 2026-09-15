#!/usr/bin/env python3
"""Builds app/src/main/assets/lexicon/nepali_lexicon.json.

The keyboard ships an offline dictionary: a list of Nepali words, the romanized
key a user is expected to type for each of them, a precomputed *relative*
frequency and, optionally, alternative roman spellings. The runtime biases
suggestions by log(frequency), so the numbers here only need to be ordered
correctly and spaced by a few orders of magnitude - they are not absolute
corpus counts.

Format (consumed by np.com.nepalikeyboard.engine.Lexicon):

    {"version": 1, "source": "...",
     "words":   [{"w": "नमस्ते", "k": "namaste", "f": 64000, "alts": ["namaste"]}],
     "bigrams": [{"a": "म", "b": "छु", "f": 90}]}

    w    Devanagari word (exactly what is committed to the editor)
    k    primary roman key, lowercase ASCII a-z (the trie is ASCII-only)
    f    relative frequency, 1..100000
    alts extra roman spellings that resolve to the same word
    a/b  Devanagari word pair for the bigram table (`a` -> `b`), f 1..100

Run:  python3 tools/generate_lexicon.py
"""

from __future__ import annotations

import json
import os
import sys

# ---------------------------------------------------------------------------
# Frequency bands. Only the ordering matters; the UI scales them with log(f+1).
# ---------------------------------------------------------------------------
FUNCTION = 90000      # pronouns, postpositions, auxiliaries, clitics
VERY_COMMON = 45000   # core verbs, everyday nouns, greetings
COMMON = 18000        # frequent nouns/adjectives/adverbs
NORMAL = 6000         # general vocabulary
LIGHT = 2000          # specialised but common
RARE = 700            # proper nouns, loanwords, festivals

# w|k|f[|alt1,alt2]
WORDS = """
म|ma|90000
मलाई|malai|80000
मेरो|mero|75000|meraa
मेरी|meri|52000
हामी|hami|70000
हाम्रो|hamro|60000
तिमी|timi|48000
तिम्रो|timro|42000
तपाईं|tapai|46000|tapai
तपाईंको|tapaiko|38000|tapaiko
उनी|uni|40000
उनको|unko|34000
उहाँ|uha|26000|uhaa
यो|yo|68000
त्यो|tyo|52000
यी|yi|20000
ती|ti|20000
यहाँ|yaha|32000|yahan
त्यहाँ|tyaha|24000|tyahan
कहाँ|kaha|30000|kahan
कहिले|kahile|24000
किन|kina|26000
कसरी|kasari|22000
कति|kati|28000
कुन|kun|20000
को|ko|44000
के|ke|50000
जे|je|16000
जो|jo|16000
सबै|sabai|30000
केही|kehi|28000
अरू|aru|24000|aru
आफ्नो|aaphno|26000|afno
आफै|aafai|12000|afai
अब|aba|30000
अझै|ajhai|14000
फेरि|pheri|18000
सधैं|sadhai|13000|sadhai
कहिल्यै|kahilyai|9000
धेरै|dherai|34000
थोरै|thorai|11000
अलि|ali|14000
निकै|nikai|9000
एकदम|ekdam|12000
साह्रै|sahrai|5000|sahrai
जति|jati|16000
उति|uti|8000
यति|yati|9000
जस्तो|jasto|20000
कस्तो|kasto|18000
त्यस्तो|tyasto|9000
यस्तो|yasto|9000
पनि|pani|52000
तर|tara|34000
कारण|karan|20000
किनभने|kinabhane|8000
लगभग|lagabhag|5000
जब|jaba|10000
तब|taba|9000
भने|bhane|24000
भनेर|bhanera|12000
कि|ki|26000
हो|ho|48000
होइन|hoina|18000
छ|chha|64000|cha
छन्|chhan|30000|chan
छु|chhu|30000|chu
छौं|chhaun|10000|chaun
थियो|thiyo|24000
थिए|thie|14000
हुन्छ|hunchha|30000|huncha
हुन्छन्|hunchhan|11000|hunchhan
हुन्छु|hunchhu|9000|hunchu
हुनेछ|hunechha|6000
हुनु|hunu|16000
पर्छ|parchha|22000|parcha
पर्छन्|parchhan|7000
परे|pare|12000
पर्ने|parne|9000
पर्यो|paryo|12000
र|ra|44000
किनकि|kinaki|4000
नै|nai|30000
मात्र|matra|20000
पनि त|pani ta|3000
खै|khai|6000
अरे|are|7000
है|hai|9000
ल|la|14000
त|ta|26000
नि|ni|18000
हँ|han|6000
नमस्ते|namaste|60000
नमस्कार|namaskar|24000
धन्यवाद|dhanyabad|42000|dhanyawad
स्वागत|swagat|12000
शुभकामना|shubhkamana|9000
बधाई|badhai|11000
क्षमा|chhyama|7000|kshama
माफ|maaph|6000|maf
आहा|aaha|4000
वाह|waha|4000
गर्नु|garnu|26000
गर्छु|garchu|24000
गर्छ|garchha|20000|garcha
गर्छन्|garchhan|12000
गर्ने|garne|16000
गरे|gare|20000
गर्यो|garyo|14000
गर्न|garna|24000
गर|gara|12000
गरेको|gareko|16000
गर्दै|gardai|10000
गरिएको|garieko|5000
भन्नु|bhannu|16000
भन्छु|bhanchhu|14000|bhanchu
भन्छ|bhanchha|16000|bhancha
भन्यो|bhanyo|12000
भनेको|bhaneko|12000
भन|bhana|10000
खानु|khanu|14000
खान्छु|khanchhu|10000|khanchu
खान्छ|khanchha|12000|khancha
खायो|khayo|9000
खाए|khae|8000
खाने|khane|10000
खाएको|khaeko|8000
पिउनु|piunu|5000
पियो|piyo|4000
जानु|jaanu|16000|janu
जान्छु|janchhu|12000|janchu
जान्छ|janchha|12000|jancha
गयो|gayo|9000
गए|gae|10000
जाने|jane|9000
आउनु|aaunu|12000|aunu
आउँछ|aaunchha|12000|auncha
आउँछु|aaunchhu|9000|aunchu
आयो|aayo|11000|ayo
आए|aae|10000|ae
हेर्नु|hernu|10000
हेर्छु|herchhu|9000|herchu
हेर्यो|heryo|8000
हेर्ने|herne|6000
सुन्नु|sunnu|8000
सुन्छु|sunchhu|7000|sunchu
सुन्यो|sunyo|5000
बोल्नु|bolnu|6000
बोल्छु|bolchhu|5000|bolchu
बोल्यो|bolyo|4000
पढ्नु|padhnu|12000
पढ्छु|padhchhu|10000|padhchu
पढ्यो|padhyo|8000
पढाउनु|padhaunu|5000
लेख्नु|lekhnu|10000
लेख्छु|lekhchhu|9000|lekhchu
लेख्यो|lekhyo|6000
सिक्नु|siknu|7000
सिकाउनु|sikaunu|5000
बुझ्नु|bujhnu|7000
बुझ्छु|bujhchhu|6000|bujhchu
जान्नु|jannu|6000
थाहा|thaha|14000
दिनु|dinu|10000
दिन्छु|dinchhu|9000|dinchu
दियो|diyo|6000
लिनु|linu|8000
लिन्छु|linchhu|7000|linchu
लियो|liyo|5000
राख्नु|rakhnu|7000
राख्छु|rakhchhu|6000|rakhchu
पाउनु|paunu|9000
पाए|pae|8000
पायो|payo|7000
बनाउनु|banaunu|8000
बनाए|banae|6000
बनायो|banayo|6000
बेच्नु|bechnu|4000
किन्नु|kinnu|5000
किने|kine|4000
तिर्नु|tirnu|4000
तिरे|tire|3000
पुग्नु|pugnu|5000
पुग्यो|pugyo|5000
चल्नु|chalnu|4000
चल्छ|chalchha|4000|chalcha
बस्नु|basnu|7000
बस्छु|baschhu|6000|baschu
उठ्नु|uthnu|5000
उठ्छु|uthchhu|4000|uthchu
उठ|utha|6000
सुत्नु|sutnu|5000
सुत्यो|sutyo|4000
दौडनु|daudanu|3000
हिँड्नु|hindnu|5000|hindnu
हिँड्यो|hindyo|4000|hindyo
पर्खनु|parkhanu|4000
पर्ख|parkha|4000
खोज्नु|khojnu|6000
खोज्यो|khojyo|5000
भेट्नु|bhetnu|6000
भेट्यो|bhetyo|5000
फेला|phela|4000
सक्नु|saknu|8000
सक्छु|sakchhu|7000|sakchu
सकियो|sakiyo|4000
थाक्नु|thaknu|3000
चाहिनु|chahinu|7000
चाहिन्छ|chahinchha|8000|chahincha
चाहन्छु|chahanchhu|6000|chahanchu
चाहियो|chahiyo|5000
मन|man|16000
मनपर्छ|manparchha|5000|manparcha
आवश्यक|aawashyak|8000|awashyak
काम|kaam|26000|kam
जीवन|jiwan|16000
संसार|sansar|12000
मान्छे|manche|18000
मानिस|manis|14000
साथी|saathi|24000|sathi
साथीभाई|saathibhai|4000
परिवार|pariwar|12000
घर|ghar|30000
कोठा|kotha|10000
ढोका|dhoka|8000
झ्याल|jhyal|6000
भान्सा|bhansa|5000
बारी|bari|4000
आँगन|aangan|4000|angan
छाना|chhana|4000|chana
भुइँ|bhuin|4000|bhui
खाट|khat|5000
पानी|paani|30000
खाना|khana|20000
भात|bhat|11000
दाल|dal|9000
तरकारी|tarkari|8000
मासु|masu|7000
माछा|machha|6000|macha
अण्डा|anda|5000
दूध|dudh|6000
दही|dahi|5000
मह|maha|4000
चिया|chiya|9000
कफी|kaphi|4000
नुन|nun|5000
तेल|tel|6000
चिनी|chini|5000
मसला|masala|4000
अचार|achar|4000
रोटी|roti|6000
चामल|chamal|5000
पिठो|pitho|4000
खुर्सानी|khursani|5000
लसुन|lasun|4000
प्याज|pyaj|4000
आलु|aalu|6000|alu
भन्टा|bhanta|4000
काउली|kauli|3000
गाजर|gajar|3000
मूला|mula|3000
काँक्रो|kakro|3000|kakro
साग|sag|6000
फलफूल|phalphul|3000
आँप|aap|5000|aap
केरा|kera|5000
स्याउ|syau|5000
सुन्तला|suntala|4000
अंगुर|angur|3000
नरिवल|naribal|3000
खरबुजा|kharbuja|2000
चामलको भात|chamalko bhat|2000
मिठाई|mithai|4000
खाजा|khaja|4000
नास्ता|nasta|3000
सर्बत|sarbat|2000
आज|aaja|34000|aja
भोलि|bholi|16000
हिजो|hijo|14000
अस्ति|asti|6000
अहिले|ahile|22000
बिहान|bihan|10000
दिउँसो|diuso|5000|diuso
बेलुका|beluka|8000
रात|raat|12000|rat
साँझ|saajh|4000|sanjh
मध्यरात|madhyaraat|2000|madhyarat
दिन|din|20000
हप्ता|hapta|6000
महिना|mahina|8000
वर्ष|barsha|10000
साल|saal|7000|sal
समय|samaya|20000
घण्टा|ghanta|6000
मिनेट|minet|4000
सेकेन्ड|sekend|3000
आइतबार|aitabar|5000
सोमबार|sombar|5000
मंगलबार|mangalbar|5000
बुधबार|budhabar|5000|budhbar
बिहीबार|bihibar|5000
शुक्रबार|shukrabar|5000
शनिबार|shanibar|5000
बैशाख|baishakh|3000
जेठ|jeth|3000
असार|asar|3000
साउन|saun|3000
भदौ|bhada|3000
असोज|asoj|3000
कात्तिक|kattik|3000
मंसिर|mansir|3000
पुस|pus|3000
माघ|magh|3000
फागुन|fagun|3000
चैत|chait|3000
एक|ek|30000
दुई|dui|26000
तीन|tin|24000
चार|char|22000
पाँच|paanch|20000|panch
सात|saat|18000|sat
आठ|aath|16000|ath
नौ|nau|16000
दश|dash|18000
एघार|eghara|6000
बाह्र|bahra|6000
तेह्र|tehra|5000
चौध|chaudh|5000
पन्ध्र|pandhra|5000
सोह्र|sohra|5000
सत्र|satra|4000
अठार|athara|4000
उन्नाइस|unnaiis|3000
बीस|bis|9000
तीस|tis|8000
चालीस|chalis|6000
पचास|pachas|6000
साठी|sathii|5000
सत्तरी|sattari|4000
असी|asi|4000
नब्बे|nabbe|3000
सय|saya|10000
हजार|hajar|10000
लाख|lakh|7000
करोड|karod|5000
पहिलो|pahilo|12000
दोस्रो|dosro|9000
तेस्रो|tesro|7000
चौथो|chautho|5000
अन्तिम|antim|7000
आधा|aadha|5000|adha
पूरा|pura|8000
नेपाल|nepal|60000
नेपाली|nepali|40000
काठमाडौं|kathmandu|30000|kathmandau
पोखरा|pokhara|10000
ललितपुर|lalitpur|6000
भक्तपुर|bhaktapur|5000
जनकपुर|janakpur|4000
धनगढी|dhangadhi|3000
विराटनगर|biratnagar|4000|biratnagar
बुटवल|butwal|3000
भरतपुर|bharatpur|3000
इलाम|ilam|2000
सोलुखुम्बु|solukhumbu|1500
मुस्ताङ|mustang|1500
अन्नपूर्ण|annapurna|1500
सगरमाथा|sagarmatha|3000
हिमाल|himal|8000
पहाड|pahad|7000
तराई|tarai|6000
खोला|khola|8000
नदी|nadi|6000
ताल|taal|6000|tal
जंगल|jangal|6000
रुख|rukh|6000
फूल|phul|8000
पात|paat|4000|pat
जमिन|jamin|7000
माटो|mato|6000
ढुंगा|dhunga|4000
बालुवा|baluwa|3000
आकाश|aakash|6000|akash
सूर्य|surya|5000
चन्द्रमा|chandrama|3000
तारा|taraa|5000
वर्षा|barshaa|4000
हावा|hawa|6000
हिउँ|hiun|4000|hiun
बादल|badal|5000
घाम|gham|6000
छाया|chhaya|3000|chaya
आगो|aago|7000|ago
देश|desh|20000
शहर|shahar|8000
गाउँ|gaun|10000|gaun
बजार|bajar|9000
पसल|pasal|5000
दोकान|dokan|4000
स्कुल|skul|9000
विद्यालय|bidyalaya|5000
कलेज|kalej|6000
विश्वविद्यालय|bishwabidyalaya|2000
पुस्तकालय|pustakalaya|2000
किताब|kitab|8000
पुस्तक|pustak|6000
कापी|kapi|5000
कलम|kalam|4000
पेन्सिल|pensil|3000
मेज|mej|4000
कुर्सी|kursi|4000
झोला|jhola|4000
कम्प्युटर|kampyutar|7000
मोबाइल|mobail|8000
फोन|fon|7000
इन्टरनेट|intarnet|5000
इमेल|imeil|4000
सन्देश|sandesh|7000
समाचार|samachar|7000
पत्रिका|patrika|4000
रेडियो|rediyo|3000
गीत|git|9000
संगीत|sangit|6000
नाच|naach|4000|nach
खेल|khel|9000
फुटबल|phutbal|5000
क्रिकेट|kriket|4000
भलिबल|bhalibal|2000
टिम|tim|4000
खेलाडी|kheladi|3000
टाउको|tauko|8000
कपाल|kapal|5000
आँखा|aankha|8000|ankha
नाक|naak|4000|nak
कान|kaan|4000|kan
मुख|mukh|5000
दाँत|daant|4000|dant
जिब्रो|jibro|3000
घाँटी|ghaanti|2000|ghanti
हात|haat|10000|hat
खुट्टा|khutta|7000
औंला|aunla|3000
पेट|pet|5000
मुटु|mutu|4000
रगत|ragat|4000
हड्डी|haddi|3000
छाला|chhala|3000|chala
शरीर|sharir|6000
स्वास्थ्य|swasthya|5000
रोग|rog|5000
औषधि|aushadhi|4000
डाक्टर|daktar|6000
अस्पताल|aspatal|6000
नर्स|nars|3000
ज्वरो|jworo|3000
रुघा|rugha|3000
खोकी|khoki|3000
दुखाइ|dukhai|3000
दुख्छ|dukhchha|4000|dukhcha
आराम|aaram|5000|aram
निद्रा|nidra|4000
सपना|sapana|5000
आनन्द|aanand|4000|anand
खुसी|khusi|10000
दुःख|dukha|7000
रिस|ris|5000
डर|dar|6000
माया|maya|14000
प्रेम|prem|7000
आशा|aasha|6000|asha
भरोसा|bharosa|5000
विश्वास|bishwas|6000
राम्रो|ramro|20000
नराम्रो|naramro|4000
ठूलो|thulo|16000
सानो|sano|16000
लामो|lamo|10000
छोटो|chhoto|7000|choto
नयाँ|nayaa|16000|naya
पुरानो|puraano|8000|purano
गह्रौं|gahraun|3000
हल्का|halka|4000
गर्म|garm|5000
चिसो|chiso|5000
मिठो|mitho|5000
पिरो|piro|3000
अमिलो|amilo|2500
नुनिलो|nunilo|2000
ताजा|taja|4000
सफा|sapha|5000
फोहोर|phohor|4000
सुन्दर|sundar|7000
सजिलो|sajilo|6000
गाह्रो|gahro|4000
महँगो|mahango|5000|mahango
सस्तो|sasto|4000
खाली|khali|5000
बलियो|baliyo|4000
कमजोर|kamjor|3000
बुद्धिमान|buddhiman|2000
छिटो|chhito|7000|chito
ढिलो|dhilo|5000
नजिक|najik|7000
टाढा|tada|6000
माथि|mathi|8000
तल|tala|5000
भित्र|bhitra|7000
बाहिर|bahira|8000
अगाडि|agadi|7000
पछाडि|pachhadi|6000|pachhadi
दायाँ|dayaa|5000|dayan
बायाँ|bayaa|5000|bayan
सिधा|sidha|4000
उल्टो|ulto|3000
खास|khaas|5000|khas
विशेष|bishesh|4000
मुख्य|mukhya|5000
सामान्य|samanya|4000
समाज|samaj|9000
राष्ट्र|rastra|6000
सरकार|sarkar|12000
कानुन|kanun|4000
अधिकार|adhikar|5000
कर्तव्य|kartavya|2000
शिक्षा|shiksha|9000
ज्ञान|gyan|7000
विज्ञान|bigyan|5000
गणित|ganit|4000
इतिहास|itihas|5000
भूगोल|bhugol|2500
भाषा|bhasha|9000
संस्कृति|sanskriti|4000
परम्परा|parampara|3000
चाड|chaad|4000|chad
दशैं|dasain|6000|dasai
तिहार|tihar|6000
होली|holi|2500
छठ|chhath|2000|chath
ल्होसार|lhosar|1500
माघे|maghe|1500
समाचारपत्र|samachhapatra|2000
सूचना|suchana|6000
जानकारी|jankari|6000
प्रश्न|prashna|5000
उत्तर|uttar|7000
समस्या|samasya|7000
समाधान|samadhan|4000
परिणाम|parinam|4000
योजना|yojana|6000
निर्णय|nirnaya|5000
सुझाव|sujhab|4000|sujhav
अनुरोध|anurodh|3000
मद्दत|maddat|6000
सहयोग|sahayog|7000|sahajog
पैसा|paisa|12000
रुपैयाँ|rupaiya|8000|rupaiya
मूल्य|mulya|5000
बिल|bil|4000
खाता|khata|5000
बैंक|bank|7000
ऋण|rin|3000
नाफा|napha|3000
घाटा|ghata|2500
बजेट|bajet|3000
कम्पनी|kampani|6000
कार्यालय|karyalaya|5000
कर्मचारी|karmachari|4000
प्रबन्धक|prabandhak|2000
ग्राहक|grahak|4000
सेवा|sewa|8000
उत्पादन|utpadan|3000
बिक्री|bikri|3000
व्यापार|byapar|5000
उद्योग|udyog|4000
कृषि|krishi|5000
किसान|kisan|5000
खेती|kheti|4000
बाली|bali|3000
मौसम|mausam|4000
सिंचाई|sinchai|1500
यात्रा|yatra|5000
गाडी|gaadi|8000|gadi
बस|bas|7000
ट्रक|trak|2000
हवाईजहाज|hawaiijahaj|2000
रेल|rel|2000
साइकल|saikal|3000
टिकट|tikat|4000
बाटो|bato|10000
पुल|pul|4000
सडक|sadak|6000
चोक|chok|4000
गल्ली|galli|3000
होटल|hotel|5000
यात्री|yatri|3000
बिदा|bida|4000
घुम्नु|ghumnu|4000
किनारा|kinara|3000
समुद्र|samudra|4000
झरना|jharana|2000
डाँडा|danda|3000|dada
उकालो|ukalo|2000
ओरालो|oralo|2000
स्कुल जानु|skul jaanu|2000
राम|ram|8000
सीता|sita|5000
गीता|gita|3000
कृष्ण|krishna|4000
शिव|shiv|4000
गणेश|ganesh|4000
लक्ष्मी|lakshmi|3000
सरस्वती|saraswati|2500
बुद्ध|buddha|3000
पृथ्वी|prithvi|3000
तारिख|tarikh|3000
मिति|miti|4000
सम्झना|samjhana|4000
सम्झनु|samjhanu|4000
बिर्सनु|birsanu|3000
रोज्नु|rojnu|3000
सोच्नु|sochnu|5000
सोच्छु|sochchhu|4000|sochchu
विचार|bichar|6000
इच्छा|ichha|3000|ichha
संकल्प|sankalp|1500
प्रयास|prayas|4000
कोसिस|kosis|4000
सफलता|safalta|3000
असफलता|asafalta|1500
मेहनत|mehnat|4000
श्रम|shram|3000
परिश्रम|parishram|2000
धैर्य|dhairya|2000
उत्साह|utsah|2500
आत्मविश्वास|aatmabishwas|1500|atmabishwas
प्रगति|pragati|4000
विकास|bikas|7000
परिवर्तन|paribartan|4000
अवस्था|awastha|4000|awastha
स्थिति|sthiti|5000
घटना|ghatana|4000
अनुभव|anubhav|4000
शुभ|shubha|6000
रात्रि|ratri|6000
लाग्यो|lagyo|8000
मा|maa|95000
लाई|lai|70000
सँग|sanga|45000|sang
हरू|haru|40000
बाट|bata|40000
देखि|dekhi|30000
ले|le|25000
सम्म|samma|25000
भयो|bhayo|20000
भए|bhae|16000
छैन|chhaina|16000|chaina
लाग्छ|lagchha|14000|lagcha
होस्|hos|12000
ठीक|thik|10000
राति|rati|8000
भर|bhara|8000
विद्यार्थी|bidyarthi|6000
देख्नु|dekhna|6000
छौ|chhau|6000|chau
जान्छौं|janchhaun|5000|janchaun
गर्छौं|garchhaun|5000|garchaun
गर्छौ|garchhau|5000|garchau
पकाउनु|pakaunu|5000
शिक्षक|shikchhak|5000|shikshak
परीक्षा|parikchha|5000|pariksha
बढ्यो|badhyo|4000
पठाउनु|pathaunu|4000
चलाउनु|chalaunu|4000
नतिजा|natija|4000
छुट्टी|chhutti|4000|chutti
कामना|kamana|3000
फर्केर|pharker|3000|pharke
चल्यो|chalyo|3000
हेलो|hello|2500
फुल्यो|phulyo|2000
"""

# a|b|f  (Devanagari word pairs; `a` is what the caret is after)
BIGRAMS = """
म|छु|100
म|गर्छु|80
म|जान्छु|70
म|खान्छु|60
म|आउँछु|50
म|पढ्छु|45
म|हेर्छु|40
म|भन्छु|38
म|बस्छु|35
म|दिन्छु|30
हामी|छौं|60
तपाईं|को|40
तपाईं|छ|30
उनी|छ|50
उनी|हो|30
यो|हो|70
यो|छ|60
त्यो|हो|50
त्यो|छ|40
यो|काम|35
यो|किताब|25
त्यो|मान्छे|25
नेपाल|को|60
नेपाली|भाषा|40
घर|को|40
घर|जान्छु|30
स्कुल|जान्छु|25
काम|गर्नु|40
काम|छ|50
काम|गर्छु|35
खाना|खानु|30
पानी|खानु|25
पानी|पिउनु|20
चिया|खानु|20
समय|छ|40
आज|बिहान|25
आज|बेलुका|20
भोलि|बिहान|25
भोलि|जान्छु|20
अहिले|समय|20
धेरै|राम्रो|35
धेरै|समय|30
धेरै|काम|30
राम्रो|छ|40
नराम्रो|छ|15
ठूलो|घर|20
सानो|छ|20
नयाँ|काम|20
पुरानो|किताब|15
माया|गर्छु|25
माया|छ|20
धन्यवाद|भन्नु|20
धन्यवाद|दिनु|15
क्षमा|गर्नु|20
माफ|गर्नु|15
नमस्ते|भन्नु|20
मन|पर्छ|20
थाहा|छ|40
केही|छ|25
केही|भन्नु|20
सबै|मान्छे|25
परिवार|को|25
खुसी|छ|20
दुःख|छ|15
आराम|छ|15
भात|खानु|20
दाल|भात|25
मिठाई|खानु|15
चामल|किन्नु|12
पैसा|छ|25
पैसा|तिर्नु|20
पैसा|दिनु|20
बजार|जानु|20
किताब|पढ्नु|20
किताब|किन्नु|15
पढ्नु|छ|15
लेख्नु|छ|15
सिक्नु|छ|15
गर्नु|छ|30
गर्नु|पर्छ|30
जानु|छ|25
जानु|पर्छ|25
थाहा|पाउनु|15
प्रश्न|छ|15
उत्तर|दिनु|15
समस्या|छ|20
समाधान|छ|12
सहयोग|गर्नु|15
मद्दत|गर्नु|15
जानकारी|दिनु|15
समाचार|पढ्नु|12
समाचार|हेर्नु|12
फोन|गर्नु|15
घर|आउनु|20
घर|बस्नु|15
काम|सकियो|15
वर्ष|को|20
बिहान|उठ्नु|15
बेलुका|खाना|15
बाटो|छ|15
हिमाल|को|20
नदी|को|10
पानी|को|15
पानी|पर्यो|20
मौसम|राम्रो|15
राम्रो|मौसम|10
स्वास्थ्य|राम्रो|12
औषधि|खानु|10
डाक्टर|कहाँ|10
अस्पताल|जानु|12
राम्रो|स्वास्थ्य|10
जीवन|को|15
संसार|को|15
देश|को|20
शिक्षा|को|15
योजना|छ|12
विकास|को|15
कृषि|को|12
बजेट|छ|8
व्यापार|गर्नु|10
सेवा|राम्रो|10
माया|गर्नु|15
विश्वास|छ|12
आशा|छ|12
यात्रा|गर्नु|12
यात्रा|राम्रो|8
घुम्नु|जानु|10
सम्झना|आयो|10
विचार|गर्नु|12
प्रयास|गर्नु|12
मेहनत|गर्नु|12
सफलता|पाउनु|10
हामी|जान्छौं|30
हामी|गर्छौं|35
तिमी|छौ|50
तिमी|गर्छौ|30
नेपाल|मा|80
भाषा|मा|20
काठमाडौं|मा|45
काठमाडौं|बाट|25
घर|मा|60
घर|फर्केर|15
स्कुल|मा|35
खाना|पकाउनु|20
समय|भयो|30
समय|मा|35
हिजो|राति|20
दिन|भर|20
राम्रो|लाग्छ|20
मन|लाग्छ|25
थाहा|भयो|20
थाहा|छैन|25
सबै|ठीक|20
साथी|हरू|35
साथी|लाई|25
साथी|सँग|20
परिवार|सँग|20
मान्छे|हरू|30
मान्छे|लाई|25
खुसी|लाग्छ|15
निद्रा|लाग्यो|15
तरकारी|पकाउनु|15
बजार|मा|25
पसल|मा|15
आउनु|होस्|20
बस्नु|होस्|15
भन्नु|होस्|15
हेर्नु|होस्|15
सुन्नु|होस्|12
मोबाइल|मा|20
मोबाइल|चलाउनु|15
कम्प्युटर|मा|15
इन्टरनेट|मा|15
इमेल|पठाउनु|12
सन्देश|पठाउनु|15
समय|भए|15
महिना|मा|20
हप्ता|मा|15
दिन|मा|20
रात|मा|20
रात|भयो|15
हेलो|छ|10
गाडी|मा|20
गाडी|चलाउनु|15
बाटो|मा|20
सडक|मा|15
आकाश|मा|12
हिमाल|मा|15
घाम|लाग्यो|12
हावा|चल्यो|10
फूल|फुल्यो|10
रुख|मा|12
जंगल|मा|12
रोग|लाग्यो|10
जीवन|मा|20
देश|मा|20
सरकार|ले|20
समाज|मा|15
विद्यालय|मा|12
कलेज|मा|12
विश्वविद्यालय|मा|8
पुस्तकालय|मा|8
शिक्षक|ले|10
विद्यार्थी|हरू|12
परीक्षा|छ|12
नतिजा|आयो|8
निर्णय|भयो|10
प्रगति|भयो|10
किसान|हरू|10
मूल्य|बढ्यो|8
ग्राहक|लाई|8
सपना|देख्नु|10
खुसी|भयो|12
दुःख|भयो|10
आनन्द|भयो|8
छुट्टी|मा|8
बिदा|मा|8
शुभ|कामना|12
शुभ|रात्रि|8
"""


def parse_words() -> list[dict]:
    words = []
    seen = set()
    for raw in WORDS.strip().splitlines():
        line = raw.strip()
        if not line:
            continue
        parts = line.split("|")
        if len(parts) < 3:
            raise ValueError(f"bad word line: {line!r}")
        devanagari, roman, freq = parts[0], parts[1], int(parts[2])
        alts = [a.strip() for a in parts[3].split(",")] if len(parts) > 3 and parts[3].strip() else []
        if devanagari in seen:
            raise ValueError(f"duplicate word: {devanagari}")
        seen.add(devanagari)
        entry = {"w": devanagari, "k": roman, "f": freq}
        if alts:
            entry["alts"] = alts
        words.append(entry)
    return words


def parse_bigrams() -> list[dict]:
    bigrams = []
    seen = set()
    for raw in BIGRAMS.strip().splitlines():
        line = raw.strip()
        if not line:
            continue
        parts = line.split("|")
        if len(parts) != 3:
            raise ValueError(f"bad bigram line: {line!r}")
        first, second, freq = parts[0], parts[1], int(parts[2])
        key = (first, second)
        if key in seen:
            raise ValueError(f"duplicate bigram: {key}")
        seen.add(key)
        bigrams.append({"a": first, "b": second, "f": freq})
    return bigrams


def main(argv: list[str] | None = None) -> None:
    """Validate the curated tables and, unless --check was passed, write the asset."""
    args = sys.argv[1:] if argv is None else argv
    check_only = "--check" in args
    unknown = [a for a in args if a != "--check"]
    if unknown:
        raise SystemExit(f"unknown argument(s): {' '.join(unknown)}")

    words = parse_words()
    bigrams = parse_bigrams()

    devanagari = {word["w"] for word in words}
    for bigram in bigrams:
        # Every bigram side should be a word the trie can also complete, so the
        # strip never predicts something the dictionary cannot explain.
        if bigram["a"] not in devanagari:
            raise ValueError(f"bigram head not in lexicon: {bigram['a']}")
        if bigram["b"] not in devanagari:
            raise ValueError(f"bigram tail not in lexicon: {bigram['b']}")

    # Roman keys must be unique: the runtime keeps the first index for a key and
    # a duplicate would silently shadow a word.
    roman_seen: dict[str, str] = {}
    for word in words:
        keys = [word["k"], *(a for a in word.get("alts", []) if a != word["k"])]
        for key in keys:
            if key in roman_seen:
                raise ValueError(f"roman key {key!r} used by {roman_seen[key]} and {word['w']}")
            roman_seen[key] = word["w"]

    payload = {
        "version": 1,
        "source": "curated-nepali-frequency-v1",
        "words": words,
        "bigrams": bigrams,
    }

    target = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "assets", "lexicon", "nepali_lexicon.json",
    )
    if check_only:
        print(f"ok: words={len(words)} bigrams={len(bigrams)} (asset not written)")
        return
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
        handle.write("\n")

    print(f"words={len(words)} bigrams={len(bigrams)} -> {target}")


if __name__ == "__main__":
    main()
