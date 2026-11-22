LOCK = """[Character reference] Follow the uploaded character image strictly: same character, same design, same art style. Do not change any details.
- Chibi girl, about 3 heads tall. Very long, fluffy orange hair with wavy ends falling past her knees; long bangs with one strand between her eyes.
- Sleepy half-lidded amber-orange eyes, soft pink blush with tiny hatch lines, small mouth, calm deadpan expression.
- Hair ornament on her left side (image right when she faces front): a large orange flower over a black ribbon bow, with cream ribbon streamers, tassels and beads hanging down.
- Outfit: cream high ruffled collar with a small orange flower brooch and a black cravat; short cream capelet embroidered with small orange flowers; cream puffy long sleeves with ruffled cuffs; black corset with gold buttons, an orange tassel and flower at the center, and a long orange ribbon sash hanging to the hem; long cream pleated skirt with a band of small embroidered orange flowers and a lace ruffle hem; black open overskirt panels with orange flowers and thin gold trim; black bows with orange tassels at the hips; cream lace-up ankle boots.
- Prop: a dark-brown hardcover book with an orange flower on the cover.
- Art style: hand-drawn crayon / colored-pencil texture, thick grainy dark-brown outlines that fully enclose every shape, soft watercolor-like flat colors with almost no hard shading; warm limited palette (orange, cream, black, dark brown, a touch of gold)."""

NEG = "plain pure white background (#FFFFFF) with no gradient, texture, paper or shadow; no ground shadow, no scenery, no text, no watermark, no frame numbers, no decorative symbols or effect marks"

def grid_spec(c, r, n, anchor):
    if c * r == 1:
        return f"[Output] One character, full body, not cropped, with generous white margins on all sides; same scale as the reference (same head size), horizontally centered; {anchor}; {NEG}."
    return (f"[Output] One image with {n} frames in an even {c}-column x {r}-row grid, read left to right, top to bottom; exactly one character per cell, "
            f"each character completely inside its own cell with generous white space around it (nothing crosses into a neighbouring cell); same size and position in every cell, "
            f"same scale as the reference (same head size); {anchor}; no divider lines; {NEG}.")

FEET = "feet on the same baseline in every frame"
GROUND = "the lowest point touching the ground on the same baseline in every frame"
BOUNCE = "all frames share one ground baseline: grounded frames stand on it, airborne frames are slightly above it"

# name: (c, r, n, aspect, anchor, refs_extra, action)
ACTIONS = {
 "turnaround": (3,1,3,"3:2","feet on one shared baseline", [], "Character turnaround sheet: three full-body standing views side by side, left to right: front, side view facing left, back. Relaxed standing pose with arms down, no book in this sheet; default sleepy half-lidded expression. In the back view show the full silhouette of the long hair and the hair ornament seen from behind; extend the back of the outfit plausibly from the front design without adding new accessories."),
 "walk": (2,2,4,"2:3",FEET, ["turnaround"], "Walk cycle in pure side profile facing LEFT, exactly like the middle side view of the turnaround sheet (we see her left side and the big flower). Clear walking poses: legs visibly apart in stepping frames. She walks with small steps, hugging the book to her chest with both hands. The four frames: 1 one foot stepping forward; 2 feet passing together, body slightly raised; 3 the other foot stepping forward; 4 feet passing together, body slightly raised. The long skirt sways gently with each step and the boot tips peek out in turn; her long hair and ribbon streamers drift behind her; keep the default sleepy half-lidded expression."),
 "drag": (1,1,1,"2:3","generous empty space above her head (where she is being held)", [], "She has been lifted off the ground by the back of her collar like a kitten and is dangling in mid-air. Knees slightly bent and relaxed, lower legs hanging loosely, heels lifted and boot toes pointing down toward the ground, the feet clearly not touching any floor; her arms hug the book to her chest; her shoulders are slightly raised and her long hair and skirt hang straight down with gravity, the skirt modestly covering her legs. Expression: resigned half-lidded eyes, a small wavy pressed mouth, light blush, as if thinking: put me down. Do not draw the hand or anything holding her."),
 "fall": (1,1,1,"2:3","character floating in the middle of the image", [], "Falling from a height. She is in mid-air, arms flung up (one hand still gripping the book), her long hair and ribbon streamers blown upward by the air; the skirt puffs out slightly like a small umbrella while still modestly covering her legs; legs slightly tucked. Flustered expression: wide round eyes, small round open mouth, a sweat drop at her temple."),
 "land": (2,1,2,"3:2",FEET, [], "Landing. Two frames: 1 the moment of impact: knees bent in a low crouch, body slightly squashed (chibi squash effect), hair and skirt spreading out to the sides, hugging the book tightly, eyes squeezed shut; 2 standing back up straight, one hand lightly brushing off her skirt, back to the sleepy expression with a hint of displeasure."),
 "sit": (2,1,2,"3:2",GROUND, [], "Sitting on the ground reading, front view. She sits with her legs folded to one side, the skirt spreading on the ground like a flower; the open book rests on her lap, head bowed as she reads, calm and focused. Two frames: 1 reading quietly 2 one hand lifting a page to turn it."),
 "sleep": (2,1,2,"3:2",GROUND, [], "Dozing off while sitting on the ground, front view. Back slightly curved, head tilted to one side, eyes closed, mouth slightly open; the open book has slid onto her lap and her long hair spreads on the ground. Two frames for breathing: in frame 2 her head and shoulders are a tiny bit lower than in frame 1. Do not draw any Z symbols."),
 "poke": (1,1,1,"2:3","feet about 5% above the bottom edge", [], "Just got poked. She flinches with her shoulders raised, eyes suddenly wide, hair and ribbon streamers bouncing up a little, hugging the book tightly; startled, with a hint of 'what was that for' annoyance. Do not draw an exclamation mark."),
 "pat": (2,1,2,"3:2",FEET, [], "Getting a head pat. She lowers her head slightly and tilts it to one side, eyes closed into contented curves, cheeks flushed, a small smile she can't quite hide, hugging the book to her chest; leave space above her head. Two frames: her head nuzzling slightly left, then slightly right. Do not draw the patting hand."),
 "read": (2,1,2,"3:2",FEET, [], "Reading while standing, front view. She holds the open book with both hands, head bowed, eyes looking down, calm. Two frames: 1 reading quietly 2 one hand turning a page, the page lifting slightly."),
 "tea": (2,1,2,"3:2",FEET, [], "Afternoon tea, front view. The book is tucked under one arm; one hand holds a cream saucer with small orange flower patterns, the other a matching teacup. Two frames: 1 eyes closed, taking a small sip 2 lowering the cup with a contented little sigh, a wisp of steam rising from the cup."),
 "yawn": (2,1,2,"3:2",FEET, [], "Getting sleepy, front view. Two frames: 1 yawning: one hand covering her mouth, eyes squinting, a small tear at the corner of the eye, book tucked under her arm; 2 stretching: both arms straight up holding the book above her head, leaning back slightly, eyes shut."),
 "curtsy": (2,1,2,"3:2",FEET, [], "An elegant curtsy, front view. Her right hand holds the book lightly against her chest, her left hand lifts one side of the skirt a little, one foot crossed behind the other, knees bent in a small bow, head slightly lowered, eyes closed with a faint smile. Two frames: 1 standing straight, just lifting the skirt 2 at the lowest point of the curtsy."),
 "wave": (2,1,2,"3:2",FEET, [], "Waving hello, front view. Her right arm hugs the book to her chest, her left hand raised beside her face, waving gently; a faint smile that keeps her sleepy, low-key personality. Two frames: palm tilted left, then right."),
 "think": (1,1,1,"2:3","feet about 5% above the bottom edge", [], "Thinking, front view. One index finger on her chin, the other arm holding the book, eyes looking up to the side, lips slightly pursed, head tilted a little. Do not draw a question mark."),
 "happy": (2,1,2,"3:2",BOUNCE, [], "Happy, front view. Hugging the book tightly to her chest, eyes curved into crescents, open-mouth smile, rosy cheeks. Two frames of a small bounce: 1 on tiptoe, slightly off the ground 2 landing back to standing."),
 "angry": (1,1,1,"2:3","feet about 5% above the bottom edge", [], "Angry in a cute way, front view. Puffed cheeks, frowning, half-lidded eyes full of disapproval, holding the book up in front of her chest as if about to give a light bonk with it. Do not draw an anger mark."),
 "shy": (1,1,1,"2:3","feet about 5% above the bottom edge", [], "Shy, front view. She hides the lower half of her face behind the open book so only her eyes show, face bright red, eyes glancing away to the side, shoulders drawn in."),
 "sad": (1,1,1,"2:3","feet about 5% above the bottom edge", [], "Hurt and teary, front view. Hugging the book tightly with both hands, eyes brimming with tears (a tear about to fall at the corner), mouth turned down, eyebrows slanted up in the middle, body drawn in."),
 "dizzy": (2,1,2,"3:2",FEET, [], "Dizzy from being shaken, front view. Swirly spiral eyes, flushed cheeks, wobbling unsteadily, hair a little messy, the book about to slip from her hands. Two frames: swaying left, then right. Do not draw stars or swirl effects around her."),
 "bored": (1,1,1,"2:3","the lowest point touching the ground about 5% above the bottom edge", [], "Bored, front view. Crouched down low in a deep squat so her whole figure is much shorter than when standing (knees up near her chest, skirt pooled on the floor around her), squatting on the ground with the long skirt draped over her legs, one arm around her knees, the other hand drawing little circles on the floor with a finger, the book lying beside her on the ground; a blank look, even sleepier than usual."),
 "hair": (1,1,1,"2:3","feet about 5% above the bottom edge", [], "Adjusting her hair ornament, front view. The book tucked under one arm, one hand raised to touch the big orange flower ornament, head tilted slightly toward that side, eyes looking down to the side, quiet expression."),
 "climb": (2,1,2,"3:2","hands and feet on the wall side touching the left edge of each cell", ["turnaround"], "Climbing a vertical wall on the left. She faces left, pure side view, her body against an invisible wall at the far left of each cell, hands gripping the wall and feet pushing against it; the book is tucked into the sash at her back; her long hair hangs down and the skirt drapes naturally; serious, slightly strained expression with a sweat drop. Two frames alternating: 1 left hand and right foot higher 2 right hand and left foot higher. Do not draw the wall."),
 "hang": (2,1,2,"3:2","the top of her hands about 3% below the top edge of each cell", [], "Hanging by both hands from an invisible horizontal edge above her, front view. Arms stretched straight up, fists gripping the edge, body in the air, legs together hanging down, the long skirt falling over her legs; the book tucked into her waist sash; calm but slightly resigned expression. Two frames: body swinging slightly left, then right. Do not draw the edge or a bar."),
}

EDITS = {
 "blink_half": "Edit only the eyes in this image: lower the upper eyelids further so the eyes are almost closed, showing only the lower part of the irises, keeping the same dark-brown crayon line texture. Everything except the eyes, including bangs, blush, hair ornament, outfit, pose, composition, size and the plain white background, must stay exactly as in the original. Do not redraw anything else.",
 "talk": "Edit only the mouth in this image: make it slightly open as if talking, a small oval with a hint of dark inside, keeping the crayon line texture. Everything except the mouth, including the eyes, bangs, blush, hair ornament, outfit, pose, composition, size and the plain white background, must stay exactly as in the original. Do not redraw anything else.",
}

STYLE = "[Style reference] Match the art style of the uploaded character image: hand-drawn crayon / colored-pencil texture, thick grainy dark-brown outlines, soft watercolor-like flat colors, warm limited palette (orange, cream, black, dark brown, a touch of gold)."
FX = {
 "emotes": ("3:2", STYLE + "\n\n[Content] A set of chibi emotion effect stickers, 4 columns x 3 rows, one standalone symbol per cell, similar in size, each centered with lots of white space around it. In order: 1 surprise lines (three short radiating strokes) 2 exclamation mark 3 question mark 4 ellipsis (three dots) 5 Zzz 6 heart 7 small five-petal orange flower 8 small twinkle star 9 sweat drop 10 cross-shaped anger vein mark 11 music note 12 dizzy swirl. Mostly orange, dark brown and cream; the sweat drop light blue and the heart orange-pink. Each symbol has a closed dark-brown crayon outline.\n\n[Output] Even 4-column x 3-row grid, no divider lines, no numbers or captions, plain pure white background (#FFFFFF), no shadows."),
}

def build(name):
    if name in EDITS:
        return EDITS[name], ["ref.png"], "2:3"
    if name in FX:
        a, p = FX[name]; return p, ["ref.png"], a
    c, r, n, aspect, anchor, extra, action = ACTIONS[name]
    refs = ["ref.png"] + [f"raw/{e}.jpg" for e in extra]
    prompt = LOCK + "\n\n[Content] " + action + "\n\n" + grid_spec(c, r, n, anchor)
    if extra:
        prompt = prompt.replace("[Character reference] Follow the uploaded character image strictly",
                                "[Character reference] The first uploaded image is the main reference and the second is a turnaround sheet of the same character; follow them strictly")
    return prompt, refs, aspect
