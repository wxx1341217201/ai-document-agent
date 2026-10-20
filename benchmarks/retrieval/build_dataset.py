"""Rebuild the fixed, assistant-curated annotation draft for the local KB 2 corpus.

The labels refer to existing document_chunk IDs. They need human review before the
numbers may be presented as a human-annotated retrieval benchmark.
"""

import json
from pathlib import Path


# Every four questions below was written against the cited local chunk text, not
# selected by looking at retrieval output. Their tag order is fixed below.
ANCHORS = [
    (250, "What is the title and author printed at the start of Alice's book?", "Which novel opens with a Lewis Carroll title page and chapter contents?", "开头注明刘易斯·卡罗尔及《爱丽丝梦游仙境》的片段是哪一段？", "Which title page is Alice's Adventures in Wonderland, rather than Pride and Prejudice?"),
    (251, "How many miles down does Alice guess she has fallen?", "What distance does Alice imagine during her descent down the rabbit hole?", "爱丽丝下坠时猜自己已经落下多少英里？", "Which passage estimates a four-thousand-mile fall, rather than a nine-foot height?"),
    (252, "What object lies on the little glass table?", "What does Alice find on a transparent table near the doors?", "玻璃小桌上放着什么，爱丽丝认为它可能开门？", "Which chunk has a golden key on a glass table, rather than a key in the rabbit's hand?"),
    (253, "What garden does Alice hope to enter after shrinking?", "Why is Alice pleased when she becomes small enough for a little door?", "爱丽丝变小后想穿过小门进入哪里？", "Which scene concerns shrinking for a garden door rather than growing against the hall roof?"),
    (254, "How tall is Alice when her head hits the hall roof?", "At what height does the ceiling stop Alice's growth in the hall?", "爱丽丝头碰到大厅屋顶时身高超过多少英尺？", "Which passage describes being over nine feet tall, rather than fitting through the garden door?"),
    (255, "Which creature grins and welcomes little fishes in Alice's recitation?", "What animal appears in Alice's mistaken poem about smiling at fish?", "爱丽丝背错的诗里，咧嘴笑并欢迎小鱼的是什么动物？", "Which passage contains the crocodile-like fish poem, rather than the Cheshire Cat's grin?"),
    (256, "What animal splashes into the pool near Alice?", "Whom does Alice discover swimming nearby after first imagining a walrus or hippopotamus?", "爱丽丝在水池里以为是海象或河马，实际看到什么？", "Which pool encounter is with a mouse rather than a puppy or pigeon?"),
    (257, "What animal is said to kill rats and be worth a hundred pounds?", "Which useful creature belongs to a farmer in Alice's story to the Mouse?", "爱丽丝提到农夫家什么动物会捉老鼠，值一百英镑？", "Which passage praises a rat-catching farm animal rather than the Mouse itself?"),
    (258, "Who proposes adjourning the meeting for more energetic remedies?", "Which bird says the group needs a stronger way to dry off?", "谁郑重提议散会，马上采用更有效的弄干办法？", "Which wet-party scene features the Dodo's proposal rather than the Mouse's sad tale?"),
    (259, "What does Alice mistake the Mouse's sad tale for?", "What wordplay arises when the Mouse announces a long and sad story?", "老鼠说自己的故事很长且悲伤，爱丽丝却看着它的什么？", "Which passage plays on tale and tail rather than the Dodo's meeting motion?"),
    (260, "Whom does the White Rabbit fear will have him executed?", "Which authority worries the Rabbit as he searches for lost things?", "白兔一边寻找东西一边害怕被谁处决？", "Which scene has the White Rabbit fearing the Duchess rather than the Queen at croquet?"),
    (261, "What happens as Alice keeps growing inside the Rabbit's house?", "How does Alice's changing size constrain her in the small room?", "爱丽丝在兔子屋里不断变大，最后为什么只能侧躺？", "Which growth scene fills a room rather than striking the hall ceiling?"),
    (263, "What falls through the window and hits Alice's face?", "What small objects are thrown into the room during the Rabbit's attempt to remove Alice?", "有什么小东西从窗户飞入，打在爱丽丝脸上？", "Which scene has a shower of pebbles rather than tarts on a courtroom table?"),
    (264, "Which small animal does Alice want to teach tricks?", "What creature seems dear to Alice although she is the wrong size to play with it?", "爱丽丝想教哪只可爱的小动物做动作？", "Which passage has a puppy rather than the farmer's rat-catching animal?"),
    (265, "Who asks Alice what she cannot remember after she mentions changing size?", "Which character hears Alice complain that her memory and size are unstable?", "爱丽丝说十分钟内身形都不固定，是谁追问她忘了什么？", "Which conversation with the Caterpillar concerns memory rather than the Cat's directions?"),
    (266, "What does the Caterpillar mean by one side and the other side?", "Which object has the two sides that might change Alice's size?", "毛毛虫所说的一边和另一边指的是哪样东西？", "Which passage explains mushroom sides rather than the two directions to the Hatter and Hare?"),
    (267, "Which bird doubts Alice's claim that she is a little girl?", "Who treats Alice's identity explanation as an invented story?", "哪只鸟不相信爱丽丝自称小女孩？", "Which identity dispute involves the Pigeon rather than the Caterpillar?"),
    (268, "What happens when the two footmen bow low?", "What accidental tangle makes Alice laugh outside the house?", "两个仆人鞠躬时发生了什么，让爱丽丝发笑？", "Which scene entangles footmen's curls rather than the tea party's cups?"),
    (269, "Which animal does the Duchess say can grin?", "What surprising ability of Cheshire cats does Alice discuss with the Duchess?", "公爵夫人说哪种猫都会咧嘴笑？", "Which grin belongs to a Cheshire cat rather than the animal in Alice's fish poem?"),
    (270, "What does Alice compare the baby's spread limbs to?", "Which sea creature provides Alice's comparison for the baby she catches?", "爱丽丝把婴儿向四周伸展的手脚比作什么？", "Which baby scene uses a starfish comparison rather than the Pigeon's snake accusation?"),
    (271, "Which two people does the Cat say live in opposite directions?", "Who can Alice visit after the Cat waves each paw toward a different home?", "柴郡猫指向两个方向，说那里分别住着谁？", "Which directions concern the Hatter and March Hare rather than the two sides of a mushroom?"),
    (272, "Who sleeps between the Hatter and March Hare at tea?", "Which drowsy guest is used as a cushion at the outdoor tea table?", "帽匠和三月兔茶桌之间睡着谁，还被当成靠垫？", "Which tea-party guest is a Dormouse rather than the Mouse in the pool?"),
    (273, "What does the March Hare dip into a cup of tea?", "Which timepiece is put into tea after a discussion of butter?", "三月兔把什么东西浸进茶杯？", "Which tea-party passage concerns a watch rather than moving to a clean cup?"),
    (274, "What time is it always according to the Hatter?", "Why do tea things remain laid out at the Hatter's table?", "帽匠说时间永远停在几点，所以茶具一直摆着？", "Which tea-party problem fixes the clock at six rather than damaging a watch with butter?"),
    (275, "Who says 'I want a clean cup' and proposes moving one place?", "Which tea guest starts the seat-shifting to obtain a fresh cup?", "谁想要干净的杯子，于是提议大家换一个座位？", "Which tea-party scene changes places rather than leaving time at six o'clock?"),
    (55, "Who urges Mr. Bennet to visit the new neighbor for his daughters?", "Why does Mrs. Bennet press her husband to meet Mr. Bingley?", "班纳特太太为了女儿们，催丈夫拜访哪位新邻居？", "Which Bingley introduction is urged by Mrs. Bennet rather than arranged by Mrs. Long?"),
    (56, "When is Mrs. Long expected back relative to the introduction?", "Why cannot Mrs. Long introduce Bingley before returning?", "朗太太回来得太晚，为什么无法先介绍彬格莱先生？", "Which passage discusses Mrs. Long's return rather than Mr. Bennet's secret visit?"),
    (57, "How do Mrs. Bennet and her five daughters seek a description of Bingley?", "Who evades the family's repeated questions about the new gentleman?", "班纳特太太和五个女儿问彬格莱的情况，谁一直不肯描述？", "Which family interrogation concerns Bingley rather than Darcy's ballroom manners?"),
    (58, "What annual income is rumored for the gentleman at the ball?", "What fortune is attributed to Darcy shortly after he enters the assembly?", "舞会上那位先生入场不久，众人传说他年收入多少？", "Which ball rumor concerns ten thousand a year rather than Bingley's two dances with Jane?"),
    (59, "How does Elizabeth respond after Darcy walks off?", "What does Elizabeth do with the slight at the evening gathering?", "达西走开后，伊丽莎白怎样向朋友讲述这件事？", "Which passage has Elizabeth telling the Darcy incident playfully rather than refusing his later dance?"),
    (60, "Who is flattered to be asked for a second dance?", "What compliment surprises Jane in the post-ball conversation?", "舞会后谁因第二次被邀跳舞而感到受宠若惊？", "Which dance conversation concerns Jane's second invitation rather than Elizabeth declining Darcy?"),
    (61, "Which house does Bingley inspect after an accidental recommendation?", "What estate catches Bingley's attention while he is still a young tenant?", "彬格莱偶然得到推荐后去看哪座房子？", "Which residence is Netherfield House rather than Longbourn or Pemberley?"),
    (62, "With whom did Bingley dance twice at the assembly?", "What repeated dance leads observers to think Bingley admires Jane?", "众人认为彬格莱欣赏简，是因为他与她跳了几次舞？", "Which passage identifies Jane as Bingley's twice-chosen partner rather than Elizabeth as Darcy's partner?"),
    (64, "How many evenings together are cited as evidence of acquaintance?", "Why does the speaker say a mere dinner would reveal only an appetite?", "除了共进晚餐，他们还一起度过了几个晚上？", "Which courtship discussion contrasts four evenings with one dinner rather than two dances?"),
    (65, "Who wants Eliza to play and sing before the company?", "Which friend opens the instrument to tease Elizabeth into performing?", "是谁打开乐器，想让伊丽莎白在人前演奏歌唱？", "Which musical pressure comes from Miss Lucas rather than Darcy's request for a dance?"),
    (66, "Whose request to dance does Elizabeth refuse?", "Which gentleman's invitation cannot sway Elizabeth even with Sir William's persuasion?", "伊丽莎白拒绝了哪位先生的跳舞邀请？", "Which dance refusal targets Darcy rather than Bingley's second dance with Jane?"),
    (67, "Which two younger Bennet sisters frequently walk to Meryton?", "Who use trips into town to enliven otherwise empty mornings?", "班纳特家哪两个小女儿常到梅里屯散步？", "Which Meryton walkers are Catherine and Lydia rather than Jane and Elizabeth?"),
    (68, "Why does Mrs. Bennet advise Jane to travel on horseback?", "What weather does Jane's mother hope will force an overnight stay?", "班纳特太太为何让简骑马，而不是坐马车出门？", "Which travel plan uses expected rain rather than Elizabeth's later walk across fields?"),
    (69, "Whom does Lydia hope to see before he goes?", "Which officer is mentioned before Elizabeth crosses fields alone?", "莉迪亚赶路时希望在离开前见到哪位军官？", "Which Meryton errand mentions Captain Carter rather than Jane's rainy ride?"),
    (70, "Who shows particular concern when Elizabeth reports on Jane at dinner?", "Which gentleman's solicitude for Jane stands out among dinner inquiries?", "伊丽莎白晚餐时汇报简的病情，谁的关切特别真诚？", "Which inquiry comes from Bingley rather than his sisters' repeated polite regrets?"),
    (71, "What do Bingley's sisters mock after he remains silent?", "Whose family connections become the sisters' joke before they visit the sick friend?", "彬格莱的姐妹们取笑她们朋友哪一方面的亲戚？", "Which passage mocks vulgar relations rather than measuring a young lady's accomplishments?"),
    (72, "Which three activities are called common accomplishments?", "What examples are given before Darcy argues that 'accomplished' is overused?", "谈到女子才艺时，文中列举了画桌子、装饰屏风和什么？", "Which passage lists netting purses rather than playing the harp?"),
    (73, "Where does Elizabeth spend most of the night while Jane is ill?", "What does Elizabeth do overnight before Bingley sends an early inquiry?", "简生病时，伊丽莎白主要在哪里过夜？", "Which passage has Elizabeth staying with her sister rather than walking through fields to reach her?"),
    (74, "What comparison does Mrs. Bennet make between country and town?", "How does Mrs. Bennet answer Darcy's slight about a country neighborhood?", "班纳特太太怎样反驳达西对乡下社交的轻视？", "Which argument defends country society rather than discussing Bingley's estate?"),
    (75, "What does the speaker say one good sonnet can do to a thin inclination?", "How might poetry affect a weak romantic attachment in this conversation?", "说话者认为一首好十四行诗会怎样影响微弱的爱慕？", "Which statement says a sonnet can starve weak love rather than nourish strong love?"),
]

CROSS_CONTEXT = [
    ([252, 253], "How does Alice use the glass-table discovery before hoping to enter the garden?"),
    ([254, 265], "How do Alice's two size problems differ between the hall and the Caterpillar conversation?"),
    ([258, 259], "What do the Dodo and the Mouse each contribute after the pool episode?"),
    ([271, 272], "Who does the Cat point Alice toward, and who is asleep at their tea table?"),
    ([273, 274], "How do the damaged watch and the permanent six o'clock tea relate?"),
    ([55, 56], "Why do Mr. Bennet's visit and Mrs. Long's return matter to introducing Bingley?"),
    ([57, 58], "How does the family's search for information about Bingley differ from the ballroom rumor about Darcy?"),
    ([65, 66], "How does Elizabeth resist both a request to perform music and a request to dance?"),
    ([55, 258], "Who urges Mr. Bennet to meet Bingley, and which Wonderland bird proposes stronger remedies after getting wet?"),
    ([58, 269], "Which ballroom gentleman is rumored to earn ten thousand a year, and which Wonderland cat is known for grinning?"),
]

UNANSWERABLE = [
    "What is Alice's email address in the story?",
    "Which Qdrant collection does the White Rabbit administer?",
    "What is Mr. Darcy's mobile phone number?",
    "Which astronaut visits Netherfield House?",
    "What SQL error code does the Hatter receive at tea?",
    "Which train does Jane take to Netherfield?",
    "What is the ISBN of the glass table in Alice's hall?",
    "Which cloud region hosts the Duchess's palace?",
    "How many Git commits did Elizabeth make at the ball?",
    "What is Lydia's credit-card expiry date?",
]


def main() -> None:
    output = Path(__file__).with_name("retrieval-dataset.jsonl")
    rows = []
    tags = ("exact", "semantic", "chinese_synonym", "distractor")
    for chunk_id, *questions in ANCHORS:
        for ordinal, (tag, question) in enumerate(zip(tags, questions, strict=True), 1):
            rows.append({"id": f"C{chunk_id}-{ordinal}", "knowledgeBaseId": 2,
                         "question": question, "answerable": True,
                         "expectedChunkIds": [chunk_id], "tags": [tag]})
    for ordinal, (chunk_ids, question) in enumerate(CROSS_CONTEXT, 1):
        tags_for_question = ["cross_context"]
        if min(chunk_ids) < 250 <= max(chunk_ids):
            tags_for_question.append("cross_document")
        rows.append({"id": f"X{ordinal:02}", "knowledgeBaseId": 2,
                     "question": question, "answerable": True,
                     "expectedChunkIds": chunk_ids, "tags": tags_for_question})
    for ordinal, question in enumerate(UNANSWERABLE, 1):
        rows.append({"id": f"N{ordinal:02}", "knowledgeBaseId": 2,
                     "question": question, "answerable": False,
                     "expectedChunkIds": [], "tags": ["unanswerable"]})
    assert len(rows) == 200 and len({row["id"] for row in rows}) == 200
    # Preserve the literary-only v2 dataset used by the earlier reports and M19B/M19C.
    output.with_name("retrieval-dataset-literary-v2.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    from technical_fixtures import build
    rows.extend(build())
    output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    print(f"Wrote {len(rows)} fixed-label cases to {output}")


if __name__ == "__main__":
    main()
