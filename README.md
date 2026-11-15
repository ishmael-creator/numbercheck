# NumberCheck: is this customer's number already registered? (Ghana pilot)

Agents type a phone number and instantly see:
🟢 **NEW, OK to register** or 🔴 **ALREADY REGISTERED**.

- **Android:** a search box, plus an automatic alert while typing the number in SurveyCTO.
- **iPhone:** a search box (installed from the same link).
- **Works offline.** Every check happens on the phone, so it's instant even with no network.
- **Light.** The phone keeps a scrambled copy of the list: about 1 MB for 200,000 numbers. Each day it downloads only that day's changes, usually a few KB.
- **Read-only.** Once a day it reads `contact_clean` from the `number_lookup` view using the read-only `number_lookup_reader` login. Nothing in Neon is created, changed or deleted.

## How it works
```
Neon (number_lookup view)
   │  10:30 every day: one read-only SELECT
   ▼
GitHub (free): builds the scrambled list + the Android app
   │  publishes to one website
   ▼
Agents' phones: download today's changes, check numbers offline
```
The query it runs:
```sql
SELECT contact_clean FROM number_lookup WHERE NOT deleted AND contact_clean <> '';
```

---

## One-time setup (about 30 minutes)

### 1. Prepare the read-only login
- If the `number_lookup_reader` password has ever been shown in a chat or email, change it first (`python setup_lookup.py`), and update Check Number's script property with the new value.
- Copy its connection string: `postgresql://number_lookup_reader:…@ep-….neon.tech/neondb?sslmode=require`

### 2. Put the project on GitHub
1. Create a GitHub account and a **new public repository** named `numbercheck`.
   It must be public for free hosting. It contains no numbers, passwords or keys: those go in secrets (next step).
2. **Add file › Upload files**: drag in everything from this folder, including the hidden `.github` folder. Commit.

### 3. Add four secrets
In the repo, go to **Settings › Secrets and variables › Actions › New repository secret**:

| Name | Value |
|---|---|
| `NEON_READER_URL` | the connection string from step 1 |
| `NUMBERCHECK_SALT` | a long random sentence you make up (e.g. `green stove 47 river moon`). It scrambles the numbers. Write it down and never change it, or every phone needs a new setup link. |
| `ANDROID_KEYSTORE_PASSWORD` | from `NumberCheck-signing-key-SECRET.txt` |
| `ANDROID_KEYSTORE_B64` | from `NumberCheck-signing-key-SECRET.txt` |

### 4. Switch on the website
1. **Settings › Pages › Source: GitHub Actions.**
2. **Actions** tab › **Build app and publish daily number list** › **Run workflow**. Wait about 5 minutes for a green tick.
3. Your site is now live at `https://YOUR-USERNAME.github.io/numbercheck/`

### 5. Make the agents' link
Open `https://YOUR-USERNAME.github.io/numbercheck/admin.html` and type your `NUMBERCHECK_SALT`. It shows the agents' link and confirms it matches the published list. Share the link privately, like a password (e.g. a WhatsApp group of agents only).

---

## Every day
**Nothing to do.** At 10:30 the list refreshes automatically from Neon. Phones pick up the changes next time they have signal.
Updated Neon late? Go to **Actions › Build app and publish daily number list › Run workflow** to refresh right away.

Safety check: if the list suddenly shrinks by more than 20% (e.g. a database problem), the job stops and keeps yesterday's list. If the drop is real, run the workflow with **force** ticked.

---

## Agent instructions

**Set up (once, on Wi-Fi)**
- **Android:** open the link in Chrome › **Download Android app** › open the file.
  - Allow "Install unknown apps" if asked.
  - If Play Protect warns: **More details › Install anyway**. It warns only because the app isn't from the Play Store.
  - Open NumberCheck › **Paste setup link** › **Save**.
  - Tap **Turn on** › find **NumberCheck** › switch on. If greyed out ("Restricted setting"): **Settings › Apps › NumberCheck › ⋮ › Allow restricted settings**, then try again.
- **iPhone:** open the link in **Safari** › **Save** › **Share › Add to Home Screen**.

**Daily use**
- **Android:** fill SurveyCTO as normal. When you type the phone number, a banner pops up at the top: 🟢 OK to register, or 🔴 already registered (the phone also vibrates). The NumberCheck app also has a search box.
- **iPhone:** open NumberCheck, type or paste the number, read the colour.
- A mistyped number does nothing. Just correct it and the answer updates.

---

## Good to know
- **Freshness:** the list is as fresh as Neon. A customer registered today appears after the next daily refresh, once their entry has reached Neon.
- **Privacy:** phones and the website hold only scrambled codes, not phone numbers. Without the salt they can't be turned back into numbers. The salt travels inside the agents' link, so keep that link private. If it leaks: set a new `NUMBERCHECK_SALT`, run the workflow, and send agents a new link.
- **Accuracy:** each number is stored as a 5-byte code. The chance of a new number being wrongly shown as registered is about 1 in 5 million checks at 200,000 numbers.
- **What the Android app reads:** only text typed in SurveyCTO, and it only reacts to full phone numbers. It sends nothing anywhere. ("Also watch other apps" is for testing only.)
- **GitHub pauses daily jobs** in a repo with no changes for 60 days. If that happens, GitHub emails you; click **Enable workflow** in the Actions tab.
- **Test first** on 2–3 Android phones with SurveyCTO Collect, to confirm the banner appears as you type.
