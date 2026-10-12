# Test draft screens

Frames from a real ranked draft (YouTube `I5dvHPFiQAw`, 1280×588), named by video timestamp.
What the detector reads from each (verified with `scraper/vision_lab.py`):

| File | Draft moment | Detected |
|---|---|---|
| draft_video1_028s.jpg | bans done, first ally pick | Vexana · bans Harley, Estes, Sun, Atlas |
| draft_video1_045s.jpg | 2 allies | + Johnson, Ixia ban |
| draft_video1_062s.jpg | 3 allies, 1 enemy | + Hanabi, Gusion, enemy Saber |
| draft_video1_080s.jpg | 4 allies, 2 enemies | + enemy Pharsa |
| draft_video1_095s.jpg | 4 enemies | + Esmeralda, Franco |
| draft_video1_110s.jpg | all 5 enemies locked | + Karrie |
| draft_video1_121s.jpg | post-draft / loading | enemy 5 = Layla (skin art), picks gone → briefing |

## How to test on the phone
1. Copy the images to the phone (or open the raw GitHub URLs below in the browser and save them).
2. In the app: **Detect in any app** on → **Start** → notification **Screen detection** → accept.
3. Open an image full-screen in landscape (Gallery / Photos). The card appears when a hero is read;
   swipe through the frames in order and watch the enemy list grow. Black bars are ignored.
4. Or play the YouTube video full-screen; the card updates live during 0:28–1:50.

Raw URLs: `https://raw.githubusercontent.com/Tasneem-Qureshi/mlcounterpicks/data/testdata/<file>`
