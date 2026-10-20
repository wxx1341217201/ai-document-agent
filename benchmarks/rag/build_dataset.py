"""Rebuild 200 source-based QA drafts for the existing KB 2 literary corpus.

Chunk ranges are resolved from the checked-in source corpus before generation,
including overlapping chunks. No model answers or retrieval predictions are used.
These are assistant-curated drafts, not completed human annotations.
"""

import json
import unicodedata
from collections import Counter
from pathlib import Path


PROVENANCE = "assistant_curated_pending_human_review"
SOURCE_CORPUS = Path(__file__).with_name("source-corpus.jsonl")

# chunk ID, English question, Chinese question, reference fact, acceptable
# phrases (preliminary suggestions only), short literal source quote.
FACTS = [
    (250, "What are the title and author of Alice's book?", "爱丽丝这本书的标题和作者是什么？", "The title is Alice's Adventures in Wonderland and the author is Lewis Carroll.", ["Alice's Adventures in Wonderland", "爱丽丝梦游仙境", "Lewis Carroll", "刘易斯·卡罗尔"], "by Lewis Carroll"),
    (251, "How many miles down does Alice guess she has fallen?", "爱丽丝猜自己已经下坠多少英里？", "Alice estimates that she has fallen four thousand miles.", ["four thousand miles", "4000 miles", "四千英里", "4000英里"], "be four thousand miles down, I think"),
    (252, "What lies on the little glass table in the hall?", "大厅里的玻璃小桌上放着什么？", "A tiny golden key lies on the glass table.", ["golden key", "金钥匙"], "there was nothing on it except a tiny golden key"),
    (253, "Where does Alice intend to go after shrinking?", "爱丽丝变小后打算去哪里？", "Alice intends to enter the garden.", ["garden", "花园"], "she decided on going into the garden at once"),
    (254, "How tall is Alice when her head hits the hall roof?", "爱丽丝的头碰到大厅屋顶时有多高？", "Alice is more than nine feet tall.", ["more than nine feet", "over nine feet", "超过九英尺", "超过9英尺"], "now more than nine feet high"),
    (255, "Which animal appears in Alice's mistaken poem about welcoming fish?", "爱丽丝背错的诗里，欢迎小鱼的是哪种动物？", "The animal in Alice's mistaken poem is a crocodile.", ["crocodile", "鳄鱼"], "How doth the little crocodile"),
    (256, "What animal is swimming in the pool near Alice?", "爱丽丝身旁水池里游泳的是什么动物？", "The animal in the pool is a mouse.", ["mouse", "老鼠"], "it was only a mouse that had slipped in like herself"),
    (257, "What kind of dog does Alice say belongs to a farmer and kills rats?", "爱丽丝说农夫养的、会捉老鼠的狗是什么品种？", "The farmer's dog is a little bright-eyed terrier.", ["terrier", "梗犬", "梗狗"], "A little bright-eyed terrier"),
    (258, "Who proposes adjourning the meeting for more energetic remedies?", "谁提出散会并立刻采用更有效的弄干办法？", "The Dodo proposes adjourning the meeting for more energetic remedies.", ["Dodo", "渡渡鸟"], "said the Dodo solemnly, rising to its feet"),
    (259, "What does Alice mistake the Mouse's long sad tale for?", "爱丽丝把老鼠的长篇悲伤故事误解成什么？", "Alice mistakes the Mouse's tale for its tail.", ["tail", "尾巴"], "It _is_ a long tail, certainly"),
    (260, "Whom does the White Rabbit fear will have him executed?", "白兔担心谁会把他处决？", "The White Rabbit fears that the Duchess will have him executed.", ["Duchess", "公爵夫人"], "The Duchess! The Duchess!"),
    (261, "Why can Alice not stand upright as she grows inside the Rabbit's house?", "爱丽丝在白兔的屋里长大后，为什么不能直立？", "Alice grows so large that there is not enough room, even for kneeling, and she must lie down.", ["lie down", "lying down", "躺", "侧躺"], "was not even room for this"),
    (263, "What small objects are thrown through the Rabbit's window at Alice?", "白兔屋子的窗户里向爱丽丝扔进了什么小东西？", "Little pebbles are thrown through the window and hit Alice's face.", ["pebbles", "小石子", "石子"], "a shower of little pebbles came rattling in at the window"),
    (264, "Which animal does Alice wish she could teach tricks?", "爱丽丝想教哪只动物做动作？", "Alice wishes she could teach the puppy tricks.", ["puppy", "小狗", "幼犬"], "I should have liked teaching it tricks very much"),
    (265, "Who asks Alice what things she cannot remember?", "谁追问爱丽丝忘记了什么事情？", "The Caterpillar asks Alice what things she cannot remember.", ["Caterpillar", "毛毛虫"], "Can’t remember _what_ things?"),
    (266, "What has the two sides the Caterpillar says will change Alice's size?", "毛毛虫说的可以改变爱丽丝身高的两边，属于什么东西？", "The two sides are sides of the mushroom.", ["mushroom", "蘑菇"], "Of the mushroom"),
    (267, "Which bird disbelieves Alice's claim to be a little girl?", "哪只鸟不相信爱丽丝自称小女孩？", "The Pigeon disbelieves Alice's claim that she is a little girl.", ["Pigeon", "鸽子"], "said the Pigeon"),
    (268, "What gets entangled when the two footmen bow?", "两个仆人鞠躬时，什么缠在了一起？", "The two footmen's curls get entangled together.", ["curls", "卷发"], "their curls got entangled together"),
    (269, "What kind of cat does the Duchess identify as grinning?", "公爵夫人说咧嘴笑的是哪种猫？", "The grinning cat is a Cheshire cat.", ["Cheshire cat", "柴郡猫"], "It’s a Cheshire cat"),
    (270, "What sea creature does Alice compare the baby's spread limbs to?", "爱丽丝把婴儿伸展的四肢比作什么海洋生物？", "Alice compares the baby's spread limbs to a starfish.", ["starfish", "star fish", "海星"], "just like a star-fish"),
    (271, "Which two characters does the Cat say live in opposite directions?", "柴郡猫说两个不同方向分别住着谁？", "The Cat points toward the Hatter and the March Hare.", ["Hatter", "帽匠", "March Hare", "三月兔"], "lives a March Hare"),
    (272, "Who sleeps between the Hatter and March Hare at the tea table?", "帽匠和三月兔之间的茶桌上睡着谁？", "A Dormouse sleeps between the Hatter and March Hare.", ["Dormouse", "睡鼠"], "a Dormouse was sitting between them, fast asleep"),
    (273, "What does the March Hare dip into tea?", "三月兔把什么浸进茶里？", "The March Hare dips a watch into his cup of tea.", ["watch", "手表", "怀表"], "The March Hare took the watch"),
    (274, "What time does the Hatter say it always is?", "帽匠说时间一直是几点？", "The Hatter says it is always six o'clock.", ["six o'clock", "six o’clock", "六点", "6点"], "It’s always six o’clock now"),
    (275, "Who asks for a clean cup and suggests moving one place?", "谁要干净杯子，并提议大家挪一个座位？", "The Hatter asks for a clean cup and suggests moving one place.", ["Hatter", "帽匠"], "I want a clean cup"),
    (55, "Who urges Mr. Bennet to visit Mr. Bingley?", "谁催班纳特先生去拜访彬格莱先生？", "Mrs. Bennet urges her husband to visit Mr. Bingley.", ["Mrs. Bennet", "班纳特太太", "贝内特太太"], "therefore you must visit him as soon as he comes"),
    (56, "When will Mrs. Long return relative to the assembly?", "朗太太会在舞会前的哪一天回来？", "Mrs. Long will return the day before the assembly.", ["day before", "前一天"], "Mrs. Long does not come back till the day before"),
    (57, "Who evades the family's attempts to obtain a description of Bingley?", "谁回避了全家想了解彬格莱外貌的追问？", "Mr. Bennet evades his wife and daughters' attempts to get a description of Bingley.", ["Mr. Bennet", "班纳特先生", "贝内特先生"], "They attacked him in various ways, with barefaced questions, ingenious suppositions, and distant surmises; but he eluded the skill of them all"),
    (58, "What yearly income is Darcy rumored to have at the assembly?", "舞会上大家传说达西每年收入多少？", "Darcy is rumored to have ten thousand a year.", ["ten thousand", "10000", "一万"], "his having ten thousand a year"),
    (59, "How does Elizabeth tell her friends about Darcy's slight?", "伊丽莎白怎样向朋友讲达西怠慢她的事？", "Elizabeth tells the story with great spirit and a playful disposition.", ["great spirit", "playful", "兴致", "活泼", "打趣"], "She told the story, however, with great spirit among her friends"),
    (60, "Who is flattered by Bingley's second invitation to dance?", "谁因彬格莱第二次邀她跳舞而受宠若惊？", "Jane is flattered by Bingley's second invitation to dance.", ["Jane", "简"], "I was very much flattered by his asking me to dance a second time"),
    (61, "Which house does Bingley inspect after an accidental recommendation?", "彬格莱偶然得到推荐后去看了哪座房子？", "Bingley inspects Netherfield House.", ["Netherfield", "尼日斐", "内瑟菲尔德"], "to look at Netherfield House"),
    (62, "With whom does Bingley dance twice at the assembly?", "彬格莱在舞会上和谁跳了两次舞？", "Bingley dances twice with Jane.", ["Jane", "简"], "Mr. Bingley had danced with her twice"),
    (64, "How many evenings together are mentioned in the discussion of Jane and Bingley's acquaintance?", "讨论简和彬格莱的相识时，提到他们一起度过了几个晚上？", "They have spent four evenings together.", ["four evenings", "四个晚上", "4个晚上"], "four evenings have been also spent together"),
    (65, "Who opens the instrument and asks Eliza to play and sing?", "谁打开乐器，请伊丽莎白演奏和歌唱？", "Miss Lucas opens the instrument and asks Eliza to play and sing.", ["Miss Lucas", "Charlotte", "卢卡斯小姐", "夏洛特"], "I am going to open the instrument, Eliza"),
    (66, "Whose dance invitation does Elizabeth refuse?", "伊丽莎白拒绝了谁的跳舞邀请？", "Elizabeth refuses Darcy's invitation to dance.", ["Darcy", "达西"], "Mr. Darcy, with grave propriety, requested to be allowed the honour of her hand, but in vain"),
    (67, "Which two younger Bennet sisters frequently visit Meryton?", "班纳特家哪两个小女儿常去梅里屯？", "Catherine and Lydia frequently visit Meryton.", ["Catherine", "Kitty", "Lydia", "凯瑟琳", "吉蒂", "莉迪亚"], "The two youngest of the family, Catherine and Lydia"),
    (68, "Why does Mrs. Bennet advise Jane to travel on horseback?", "班纳特太太为什么建议简骑马去尼日斐庄园？", "Mrs. Bennet expects rain, which would make Jane stay at Netherfield overnight.", ["rain", "雨", "stay all night", "留宿", "过夜"], "it seems likely to rain; and then you must stay all night"),
    (69, "Which officer does Lydia hope to see before he goes?", "莉迪亚希望在谁离开前见到他？", "Lydia hopes to see Captain Carter before he goes.", ["Captain Carter", "卡特上尉", "卡特"], "see something of Captain Carter, before he goes"),
    (70, "Who shows superior concern for Jane during dinner inquiries?", "晚餐询问简的病情时，谁特别关切？", "Mr. Bingley shows superior solicitude for Jane.", ["Bingley", "彬格莱", "宾利"], "the much superior solicitude of Mr. Bingley"),
    (71, "What do Bingley's sisters ridicule about their friend's relations?", "彬格莱的姐妹们嘲笑她们朋友的亲戚什么？", "Bingley's sisters ridicule their friend's vulgar relations.", ["vulgar", "粗俗", "鄙俗"], "their dear friend’s vulgar relations"),
    (72, "What three skills are listed as common accomplishments?", "文中列举女子通常都会的哪三种才艺？", "The listed skills are painting tables, covering screens, and netting purses.", ["paint tables", "cover screens", "net purses", "画桌子", "屏风", "编织钱袋"], "They all paint tables, cover screens, and net purses"),
    (73, "Where does Elizabeth spend most of the night while Jane is ill?", "简生病时，伊丽莎白大半夜在哪里？", "Elizabeth spends most of the night in her sister's room.", ["sister's room", "sister’s room", "姐姐房间", "简的房间"], "passed the chief of the night in her sister’s room"),
    (74, "What does Mrs. Bennet claim about country society compared with town?", "班纳特太太怎样比较乡下与城里的社交活动？", "Mrs. Bennet claims there is as much of that going on in the country as in town.", ["as much", "一样多", "同样多", "不比城里少"], "going on in the country as in town"),
    (75, "What can one good sonnet do to a slight romantic inclination?", "一首好十四行诗会怎样影响微弱的爱慕？", "One good sonnet can starve a slight inclination entirely away.", ["starve", "消失", "消灭", "消退"], "one good sonnet will starve it entirely away"),
]

# Compound answers are split into atomic required facts so partial answers
# cannot meet the 100% threshold by mentioning only one of the requested items.
ATOMIC_FACTS = {
    250: [("title", "The title is Alice's Adventures in Wonderland.", ["Alice's Adventures in Wonderland", "爱丽丝梦游仙境"], "Alice’s Adventures in Wonderland"),
          ("author", "The author is Lewis Carroll.", ["Lewis Carroll", "刘易斯·卡罗尔"], "by Lewis Carroll")],
    271: [("hatter", "The Cat points toward the Hatter.", ["Hatter", "帽匠"], "lives a Hatter"),
          ("hare", "The Cat points toward the March Hare.", ["March Hare", "三月兔"], "lives a March Hare")],
    67: [("catherine", "Catherine is one of the frequent Meryton visitors.", ["Catherine", "Kitty", "凯瑟琳", "吉蒂"], "Catherine and Lydia"),
         ("lydia", "Lydia is one of the frequent Meryton visitors.", ["Lydia", "莉迪亚"], "Catherine and Lydia")],
    68: [("rain", "Mrs. Bennet expects it to rain.", ["rain", "雨"], "it seems likely to rain"),
         ("overnight", "The rain would make Jane stay overnight.", ["stay all night", "overnight", "留宿", "过夜"], "you must stay all night")],
    72: [("tables", "Painting tables is listed.", ["paint tables", "painting tables", "画桌子"], "paint tables"),
         ("screens", "Covering screens is listed.", ["cover screens", "covering screens", "屏风"], "cover screens"),
         ("purses", "Netting purses is listed.", ["net purses", "netting purses", "编织钱袋"], "net purses")],
}

MULTI = [
    (252, 253, "What does Alice find on the glass table, and where does she intend to go after shrinking?", "爱丽丝在玻璃桌上找到什么，变小后又想去哪里？"),
    (251, 254, "Compare Alice's estimated falling distance with her height when she hits the hall roof, including the units.", "比较爱丽丝猜测的下坠距离和她碰到屋顶时的身高，并注明单位。"),
    (255, 269, "Which animal welcomes fish in Alice's poem, and which kind of cat grins at the Duchess's house?", "诗里欢迎小鱼的动物与公爵夫人家咧嘴笑的猫，分别是什么？"),
    (256, 257, "Distinguish the animal Alice meets in the pool from the farmer's rat-catching dog she describes.", "区分爱丽丝在水池里遇到的动物与她描述的农夫的捕鼠狗。"),
    (258, 259, "Identify the bird proposing stronger remedies and explain what Alice confuses the Mouse's tale with.", "提出更强弄干办法的鸟是谁，爱丽丝又把老鼠的故事误解成什么？"),
    (260, 261, "Who threatens the Rabbit in his own worries, and why can growing Alice not stand upright inside his house?", "白兔担心被谁处决，长大的爱丽丝又为何不能在他的屋里直立？"),
    (263, 264, "What is thrown at Alice through the window, and what animal does she later wish to teach tricks?", "窗外向爱丽丝扔入了什么，她后来想教哪种动物做动作？"),
    (265, 266, "Who questions Alice about her memory, and what object has the two sides that change her size?", "谁追问爱丽丝的记忆问题，改变她身高的两边又属于什么东西？"),
    (267, 268, "Which bird doubts Alice's identity, and what tangles when the two footmen bow in the next episode?", "哪只鸟怀疑爱丽丝的身份，后面的两个仆人鞠躬时什么缠在一起？"),
    (271, 272, "Which two people does the Cat direct Alice toward, and who sleeps between them at tea?", "柴郡猫指引爱丽丝去找哪两个人，茶桌上睡在他们之间的又是谁？"),
    (273, 274, "What timepiece does the March Hare put into tea, and what time is permanently fixed according to the Hatter?", "三月兔把什么计时物放进茶里，帽匠又说时间固定在几点？"),
    (274, 275, "Explain the tea party's fixed time and identify the person who asks everyone to move for a clean cup.", "说明茶会的固定时间，并指出谁为了干净杯子要求大家换座位。"),
    (55, 56, "Who pushes Mr. Bennet to visit Bingley, and why is Mrs. Long's return too late for an introduction?", "谁催班纳特先生拜访彬格莱，朗太太又会在舞会前何时回来？"),
    (57, 58, "Contrast the person withholding a description of Bingley with the income rumor about Darcy.", "说出谁回避了关于彬格莱的追问，以及关于达西年收入的传言。"),
    (58, 62, "What income is attributed to Darcy, and with whom does Bingley dance twice?", "达西被传有多少年收入，彬格莱又和谁跳了两次舞？"),
    (61, 68, "Name Bingley's inspected house and explain the weather-based reason Jane is sent there on horseback.", "说出彬格莱查看的房子，并解释让简骑马去那里的天气理由。"),
    (65, 66, "Who presses Eliza to perform music, and whose dance invitation does she refuse?", "谁催伊丽莎白演奏歌唱，她又拒绝了谁的跳舞邀请？"),
    (67, 69, "Which two sisters regularly visit Meryton, and which officer does Lydia hope to meet before he leaves?", "哪两个姐妹常去梅里屯，莉迪亚希望在谁离开前见到他？"),
    (70, 73, "Who shows marked concern at dinner about Jane, and where does Elizabeth spend most of that night?", "晚餐时谁特别关心简，伊丽莎白那晚大部分时间又待在哪里？"),
    (72, 75, "List the three common accomplishments and describe the effect of a sonnet on a slight inclination.", "列出文中的三种常见才艺，并说明十四行诗对微弱爱慕的作用。"),
]

CROSS = [
    (251, 58, "Compare Alice's imagined descent and Darcy's rumored income; identify the different units.", "对比爱丽丝想象的下坠距离与达西传说中的年收入，说明各自的单位。"),
    (252, 61, "Across the two books, what does Alice find on the glass table and which house does Bingley inspect?", "跨两本书回答：爱丽丝在玻璃桌上找到什么，彬格莱查看哪座房子？"),
    (256, 67, "Contrast Alice's pool companion with the two Bennet sisters who regularly go to Meryton.", "比较爱丽丝的水池同伴与常去梅里屯的两位班纳特姐妹。"),
    (258, 55, "Who proposes action for the wet Wonderland group, and who urges a visit to Bingley in the other book?", "两本书中，谁为湿透的仙境小组提出行动，谁又催人拜访彬格莱？"),
    (259, 75, "Explain the Mouse tale/tail misunderstanding and the claimed effect of a sonnet on weak affection.", "说明老鼠故事与尾巴的误会，以及另一本文档里十四行诗对微弱爱慕的作用。"),
    (260, 69, "Identify whom the White Rabbit fears and which departing officer Lydia wants to see.", "指出白兔惧怕的人，以及莉迪亚想在其离开前见到的军官。"),
    (264, 70, "What animal does Alice want to train, and which gentleman is particularly concerned about Jane?", "爱丽丝想训练什么动物，另一本文档里哪位先生特别关心简？"),
    (265, 57, "Contrast the questioner about Alice's memory with the person avoiding questions about Bingley.", "比较追问爱丽丝记忆的人与回避关于彬格莱问题的人。"),
    (266, 68, "What changes Alice's size, and what weather does Mrs. Bennet expect to keep Jane away overnight?", "什么东西的两边能改变爱丽丝身高，班纳特太太又期盼什么天气让简留宿？"),
    (268, 59, "What makes Alice laugh when the footmen bow, and how does Elizabeth recount Darcy's slight?", "仆人鞠躬时什么使爱丽丝发笑，伊丽莎白又怎样讲述达西的怠慢？"),
    (269, 58, "Name the grinning cat in Wonderland and state the rumored yearly income of Darcy.", "说出仙境里咧嘴笑的猫，并给出达西传说中的年收入。"),
    (270, 72, "What is the baby's limb comparison in Alice, and what three everyday accomplishments are listed in Pride and Prejudice?", "爱丽丝将婴儿四肢比作什么，《傲慢与偏见》又列举哪三种常见才艺？"),
    (271, 66, "Whom does the Cat suggest Alice can visit, and whose invitation does Elizabeth refuse?", "柴郡猫推荐爱丽丝拜访谁，伊丽莎白又拒绝了谁的邀请？"),
    (272, 73, "Compare the sleeping guest's place at the tea table with Elizabeth's overnight location while Jane is sick.", "比较茶桌上熟睡的客人及其位置与简生病时伊丽莎白的过夜地点。"),
    (274, 56, "Contrast the Hatter's permanent time with Mrs. Long's return relative to the assembly.", "比较帽匠说的永久时间与朗太太在舞会前的返回时间。"),
]

TURNS = [
    (251, "We are discussing Alice's fall down the rabbit hole.", "我们正在讨论爱丽丝掉下兔子洞。", "How many miles did she think she had fallen?", "她认为自己已经掉下多少英里？"),
    (252, "Let's focus on Alice's little glass table in the hall.", "我们接着讨论爱丽丝在大厅见到的玻璃小桌。", "What was on it?", "它上面放着什么？"),
    (254, "We are at Alice's growth scene in the hall.", "话题是爱丽丝在大厅变大的那一幕。", "How tall was she when she hit the roof?", "她碰到屋顶时到底有多高？"),
    (256, "We are talking about the creature splashing near Alice in the pool.", "我们说的是爱丽丝水池附近溅水的动物。", "What animal was it actually?", "实际上它是什么动物？"),
    (259, "Continue with Alice listening to the Mouse's long sad tale.", "继续说爱丽丝听老鼠讲长篇悲伤故事那一幕。", "What did she confuse it with?", "她把它和什么混淆了？"),
    (266, "Let's discuss the Caterpillar's remark about one side and the other side.", "我们来谈毛毛虫关于一边和另一边的说法。", "What object did those sides belong to?", "所说的两边属于什么东西？"),
    (269, "The current topic is the Duchess explaining why her cat grins.", "当前话题是公爵夫人解释自己的猫为什么咧嘴笑。", "What kind of cat was it?", "它是哪种猫？"),
    (272, "We are looking at the Hatter and March Hare's tea table.", "我们正在看帽匠与三月兔的茶桌那一幕。", "Who was asleep between them?", "在他们之间睡着的是谁？"),
    (274, "We are discussing the Hatter's complaint about Time.", "我们正在谈帽匠对时间的抱怨。", "What time did he say it always was?", "他说时间一直是几点？"),
    (58, "Let's return to the ballroom rumor about Darcy's fortune.", "回到舞会上关于达西财富的传闻。", "How much did he supposedly earn per year?", "传说他每年有多少收入？"),
    (61, "The subject is the house Bingley inspected after a recommendation.", "话题是彬格莱获推荐后查看的房子。", "What was that house called?", "那座房子叫什么？"),
    (67, "We are discussing the younger Bennet sisters' regular Meryton trips.", "我们正谈班纳特家小女儿经常去梅里屯的事。", "Which two sisters were they?", "是哪两个姐妹？"),
    (68, "Jane's proposed horseback trip is the current topic.", "当前话题是简计划骑马出门。", "Why did her mother recommend that way of travelling?", "她母亲为什么建议用这种出行方式？"),
    (73, "We are discussing Elizabeth staying near Jane during Jane's illness.", "我们正谈简生病时伊丽莎白留下陪伴她。", "Where did she spend most of that night?", "那一晚她大部分时间在哪里？"),
    (75, "We are considering the conversation about a sonnet and slight affection.", "我们正讨论十四行诗与微弱爱慕的对话。", "What effect could it have on that inclination?", "它会对这种微弱的爱慕产生什么作用？"),
]

UNANSWERABLE = [
    "What is Alice's email address in the story?",
    "What is Mr. Darcy's mobile phone number?",
    "Which Qdrant collection does the White Rabbit administer?",
    "Which astronaut visits Netherfield House?",
    "What SQL error code does the Hatter receive at tea?",
    "What is Lydia's credit-card expiry date?",
    "爱丽丝的电子邮箱地址是什么？",
    "达西先生的手机号码是多少？",
    "公爵夫人的宫殿部署在哪个云计算区域？",
    "伊丽莎白在舞会上提交了多少个 Git commit？",
]


def normalize(value):
    value = unicodedata.normalize("NFKC", value).lower()
    return " ".join("".join(" " if unicodedata.category(ch)[0] in "PZ" or ch.isspace() else ch
                            for ch in value).split())


def build_rows():
    facts = {row[0]: row for row in FACTS}
    corpus = [json.loads(line) for line in SOURCE_CORPUS.read_text(encoding="utf-8").splitlines() if line.strip()]
    for chunk in corpus:
        chunk["normalizedContent"] = normalize(chunk["content"])

    def supporting_chunks(anchor, quote):
        document_id = 5 if anchor >= 250 else 2
        normalized_quote = normalize(quote)
        matches = sorted(chunk["chunkId"] for chunk in corpus if chunk["knowledgeBaseId"] == 2
                         and chunk["documentId"] == document_id and normalized_quote in chunk["normalizedContent"])
        if not matches:
            raise ValueError(f"Source quote is absent from document {document_id}: {anchor}")
        return matches

    def required(chunk_id):
        _, _, _, answer, aliases, quote = facts[chunk_id]
        parts = ATOMIC_FACTS.get(chunk_id, [("", answer, aliases, quote)])
        return [{"id": f"F{chunk_id}{'-' + suffix if suffix else ''}", "statement": statement,
                 "acceptedPhrases": phrases, "supportingChunkIds": supporting_chunks(chunk_id, source), "sourceQuote": source}
                for suffix, statement, phrases, source in parts]

    def sample(case_id, question, kind, chunk_ids, history=None):
        required_facts = [fact for c in chunk_ids for fact in required(c)]
        return {"id": case_id, "knowledgeBaseId": 2, "question": question, "type": kind,
                "answerable": bool(chunk_ids),
                "referenceAnswer": " ".join(facts[c][3] for c in chunk_ids) if chunk_ids else
                                   "我不知道。现有资料不足以回答这个问题，请补充相关资料。",
                "requiredFacts": required_facts, "factCoverageThreshold": 1.0,
                "acceptableChunkIds": sorted({c for fact in required_facts for c in fact["supportingChunkIds"]}), "history": history or [],
                "labelProvenance": PROVENANCE}

    rows = []
    for chunk_id, english, chinese, *_ in FACTS:
        for language, question in (("en", english), ("zh", chinese)):
            rows.append(sample(f"S{chunk_id}-{language}", question, "SINGLE_DOCUMENT", [chunk_id]))
    for prefix, kind, pairs in (("M", "MULTI_CHUNK", MULTI), ("X", "CROSS_DOCUMENT", CROSS)):
        for ordinal, (first, second, english, chinese) in enumerate(pairs, 1):
            for language, question in (("en", english), ("zh", chinese)):
                rows.append(sample(f"{prefix}{ordinal:02}-{language}", question, kind, [first, second]))
    for ordinal, (chunk_id, topic_en, topic_zh, question_en, question_zh) in enumerate(TURNS, 1):
        for language, topic, question in (("en", topic_en, question_en), ("zh", topic_zh, question_zh)):
            # Visible history only resolves the topic; it never supplies the reference fact.
            history = [{"role": "USER", "content": topic},
                       {"role": "ASSISTANT", "content": "Let's continue with that passage." if language == "en" else "我们继续讨论该段落。"}]
            rows.append(sample(f"T{ordinal:02}-{language}", question, "MULTI_TURN", [chunk_id], history))
    for ordinal, question in enumerate(UNANSWERABLE, 1):
        rows.append(sample(f"N{ordinal:02}", question, "UNANSWERABLE", []))
    assert len(rows) == 200 and len({r["id"] for r in rows}) == 200
    return rows


def validate_source_quotes():
    import hashlib
    metadata = json.loads(SOURCE_CORPUS.with_name("source-corpus-metadata.json").read_text(encoding="utf-8"))
    if hashlib.sha256(SOURCE_CORPUS.read_bytes()).hexdigest() != metadata["sha256"]:
        raise ValueError("The frozen source corpus has changed; fix provenance before rebuilding labels")


if __name__ == "__main__":
    validate_source_quotes()
    rows = build_rows()
    output = Path(__file__).with_name("qa-dataset.jsonl")
    output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8", newline="\n")
    print(f"Wrote {len(rows)} draft QA: {dict(Counter(row['type'] for row in rows))}")
