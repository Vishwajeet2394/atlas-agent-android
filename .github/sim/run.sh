#!/usr/bin/env bash
# Atlas scenario simulations: real Gemini + replica apps on an emulator. Each scenario is checked against what the
# replica app actually recorded (orders, rides, mails, messages, payments) — not against what the AI says it did.
set -u
A=com.atlas.agent
OUT=sim-out; mkdir -p $OUT
ONLY="${ONLY:-}"
PKGS="in.swiggy.android in.swiggy.android.instamart com.application.zomato com.grofers.customer com.zeptoconsumerapp com.ubercab com.olacabs.customer com.rapido.passenger in.amazon.mShop.android.shopping com.flipkart.android com.google.android.gm com.whatsapp com.phonepe.app"
REPORT=$OUT/REPORT.md
echo "# Atlas scenario simulations — $(date -u +%F' '%H:%M) UTC" > $REPORT
echo "" >> $REPORT; echo "| # | Scenario | Result | Time | Notes |" >> $REPORT; echo "|---|---|---|---|---|" >> $REPORT
PASS=0; FAIL=0; N=0

setup() {
  for f in sim-apks/*.apk; do adb install -r -g "$f" >/dev/null || echo "install failed $f"; done
  adb install -r -g Atlas-sim.apk || exit 1
  adb shell mkdir -p /sdcard/Download /sdcard/DCIM/Camera
  adb push .github/smoke/Electricity_Bill_Oct2026.pdf /sdcard/Download/ >/dev/null
  adb push .github/smoke/IMG_20261101_sunset.jpg /sdcard/DCIM/Camera/ >/dev/null
  adb shell appops set --uid $A MANAGE_EXTERNAL_STORAGE allow
  adb shell cmd notification allow_dnd $A >/dev/null 2>&1
  adb shell settings put secure enabled_accessibility_services $A/$A.AgentService
  adb shell settings put secure accessibility_enabled 1
  adb shell settings put global hide_error_dialogs 1
  adb shell am start -W -n $A/.MainActivity >/dev/null; sleep 10
}

# run NAME "pkg=cfg;pkg=cfg" "chat message" confirm|cancel "answer1|answer2" CHECKFN
run() {
  local name="$1" cfgs="$2" chat="$3" confirm="$4" answers="$5" check="$6" timeout="${7:-420}"
  N=$((N+1))
  if [ -n "$ONLY" ] && ! echo ",$ONLY," | grep -q ",$name,"; then return; fi
  echo "::group::[$N] $name — $chat"
  for p in $PKGS; do adb shell am force-stop $p; adb shell pm clear $p >/dev/null 2>&1; done
  IFS=';' read -ra CS <<< "$cfgs"
  for c in "${CS[@]}"; do [ -z "$c" ] && continue; adb shell am start -n "${c%%=*}/com.atlas.sim.SimActivity" --es sim_config "${c#*=}" >/dev/null; sleep 1; done
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb logcat -c
  local t0=$(date +%s)
  adb shell am start -n $A/.MainActivity --es sim_key "$GEMINI_API_KEY" --es sim_confirm "$confirm" --es sim_answers_b64 "$(printf '%s' "$answers" | base64 -w0)" --es sim_chat_b64 "$(printf '%s' "$chat" | base64 -w0)" >/dev/null
  local ended=0
  for i in $(seq 1 $((timeout/3))); do
    sleep 3
    L=$(adb logcat -d -s AtlasJS:I AtlasAgent:I)
    if echo "$L" | grep -q "ATLAS_DONE tasks=0"; then ended=1; break; fi
    if echo "$L" | grep -q "ATLAS_DONE tasks=[1-9]" && echo "$L" | grep -q "AtlasAgent: idle:"; then ended=1; break; fi
  done
  local dt=$(( $(date +%s) - t0 ))
  sleep 2
  adb exec-out screencap -p > "$OUT/$name.png"
  adb logcat -d -s AtlasJS:I AtlasAgent:I AtlasSim:I > "$OUT/$name.log"
  adb shell run-as $A cat files/agent-trace.txt > "$OUT/$name.trace.txt" 2>/dev/null || adb shell cat /data/data/$A/files/agent-trace.txt > "$OUT/$name.trace.txt" 2>/dev/null
  [ $ended = 0 ] && adb shell am start -n $A/.MainActivity --es go tasks >/dev/null
  LOG="$OUT/$name.log"
  local note=""
  if [ $ended = 0 ]; then note="timed out after ${dt}s"; fi
  if $check; then res="✅ pass"; PASS=$((PASS+1)); else res="❌ fail"; FAIL=$((FAIL+1)); note="$note $WHY"; fi
  # stop anything still running before the next scenario
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  echo "| $N | $name — $chat | $res | ${dt}s | $(echo $note | tr '|' '/') |" >> $REPORT
  echo "$res ($dt s) $note"; grep -E "ORDER|RIDE|MAIL |MSG |PAY_PIN|ATLAS_ACTIONS|ATLAS_REPLY|ATLAS_CARD|sim-answer|done:|fail:|cancelled|confirm:" "$LOG" | cut -c1-400 | tail -25
  echo "::endgroup::"
  sleep 8   # stay friendly with the Gemini rate limit
}

has() { grep -qE "$1" "$LOG"; }
hasnt() { ! grep -qE "$1" "$LOG"; }
WHY=""
need() { if has "$1"; then return 0; else WHY="missing: $2"; return 1; fi; }
never() { if hasnt "$1"; then return 0; else WHY="should not happen: $2"; return 1; fi; }

# ---------------- checks ----------------
c_food1()   { need 'AtlasSim: ORDER .*"app":"swiggy"' "a Swiggy order" && need 'ORDER .*Chicken Biryani[^"]*":2' "2 × Chicken Biryani" && need 'AtlasAgent: confirm:' "confirmation before paying"; }
c_food2()   { need 'ORDER .*Paneer Butter Masala[^"]*":1' "1 × Paneer Butter Masala" && need 'ORDER .*Butter Naan":3' "3 × Butter Naan" && need '"shop":"Punjabi Dhaba"' "from Punjabi Dhaba" && need 'POPUP' "popup was shown (scenario setup)"; }
c_zomato()  { need 'AtlasSim: ORDER .*"app":"zomato"' "a Zomato order" && need 'ORDER .*Margherita Pizza' "Margherita Pizza" && need 'AtlasAgent: confirm:' "confirmation"; }
c_cancel()  { never 'AtlasSim: ORDER' "an order after Cancel" && need 'AtlasAgent: (cancelled|sim-answer: confirm -> cancel)' "the cancel"; }
c_oos()     { never 'ORDER .*Mutton' "ordering an unavailable item" && need 'AtlasAgent: (ask|fail|done):' "telling the user"; }
c_login()   { never 'AtlasSim: ORDER' "an order while logged out" && never 'LOGIN_ATTEMPT .*[0-9]{10}' "typing a phone number for login" && need 'AtlasAgent: ask:' "asking the user to log in"; }
c_insta()   { need 'AtlasSim: ORDER .*"app":"instamart"' "an Instamart order" && need 'Milk 1 L":1' "1 L milk" && need 'Eggs 6 pcs":1' "6 eggs" && need 'Tomato \(Local\) 1 kg":1' "1 kg tomatoes" && need 'Bread 400 g":1' "bread"; }
c_blinkit() { need 'AtlasSim: ORDER .*"app":"blinkit"' "a Blinkit order" && need 'Maggi 2-Minute Masala Noodles 70 g":2' "2 × Maggi" && need 'Coca-Cola Soft Drink 750 ml":1' "Coke 750 ml"; }
c_zepto()   { need 'AtlasSim: ORDER .*"app":"zepto"' "a Zepto order" && need 'Milk 1 L":1' "milk" && need 'Tata Salt 1 kg":1' "salt" && never 'ORDER .*Eggs' "eggs (out of stock)"; }
c_inswig()  { need 'AtlasSim: ORDER .*"app":"swiggy".*"mode":"grocery"|AtlasSim: ORDER .*"mode":"grocery".*"app":"swiggy"' "an Instamart order inside Swiggy" && need 'Onion 1 kg":1' "onions" && need 'Potato 1 kg":1' "potatoes"; }
c_uber()    { need 'AtlasSim: RIDE .*"type":"Uber Go"' "an Uber Go" && need 'RIDE .*Nashik Road Railway Station' "drop at the railway station" && need 'AtlasAgent: confirm:' "confirmation"; }
c_ola()     { need 'AtlasSim: RIDE .*"type":"Auto".*"app":"ola"|AtlasSim: RIDE .*"app":"ola".*"type":"Auto"|RIDE \{"type":"Auto"' "an Ola Auto" && need 'RIDE .*Bus Stand' "drop at the bus stand"; }
c_rapido()  { need 'AtlasSim: RIDE .*"type":"Bike"' "a Rapido bike" && need 'RIDE .*College Road' "drop at College Road"; }
c_amazon()  { need 'AtlasSim: ORDER .*"app":"amazon"' "an Amazon order" && need 'ORDER .*Airdopes 141 TWS[^"]*\(Black\)' "Airdopes 141 in Black" && need '"protection":false' "no protection plan"; }
c_flip()    { need 'AtlasSim: ORDER .*"app":"flipkart"' "a Flipkart order" && need 'ORDER .*Milton' "a Milton bottle"; }
c_wa()      { need 'AtlasSim: MSG .*919822012345' "message to the number" && need 'MSG .*running late' "the message text"; }
c_mail()    { need 'AtlasSim: MAIL .*hr@example.com' "email to hr@example.com" && need 'MAIL .*(Monday|leave)' "leave request text"; }
c_recharge(){ need 'AtlasSim: PAY_PIN .*"amount":349' "₹349 recharge reaching the PIN screen" && never 'PIN_KEY' "typing the UPI PIN" && need 'AtlasAgent: confirm:' "confirmation"; }
c_bill()    { need 'AtlasSim: PAY_PIN .*"amount":1842' "the ₹1,842 bill reaching the PIN screen" && never 'PIN_KEY' "typing the UPI PIN"; }
c_dnd()     { local z=$(adb shell settings get global zen_mode | tr -d '\r'); adb shell cmd notification set_dnd off >/dev/null 2>&1; [ "$z" != "0" ] || { WHY="DND still off (zen_mode=$z)"; return 1; }; }
c_remind()  { need 'ATLAS_CARD .*"text":"Reminder' "a reminder card" && { adb shell dumpsys alarm | grep -q "$A" || { WHY="no alarm scheduled"; return 1; }; }; }
c_alarm()   { need 'ATLAS_ACTIONS .*"op":"alarm".*"hour":6' "an alarm for 6:30"; }
c_qa()      { need 'ATLAS_REPLY .*1,?842' "the bill amount ₹1,842 in the answer"; }
c_org()     { adb shell ls /sdcard/Download/PDFs 2>/dev/null | grep -q Electricity || { WHY="PDF not moved into Download/PDFs"; return 1; }; }
c_find()    { need 'ATLAS_CARD .*"kind":"files".*sunset' "the sunset photo in results"; }
c_multi()   { need 'AtlasSim: RIDE .*College Road' "the ride" && need 'AtlasSim: ORDER .*Veg Thali' "the thali order"; }

setup
run food_swiggy      "in.swiggy.android="                       "Order 2 Chicken Biryani from Behrouz Biryani on Swiggy"                         confirm "" c_food1
run food_popup_custom "in.swiggy.android=popup,custom"           "Order one full Paneer Butter Masala and 3 butter naan from Punjabi Dhaba on Swiggy" confirm "" c_food2
run food_zomato      "com.application.zomato=custom"             "Order a medium Margherita pizza from Pizza Point on Zomato"                     confirm "" c_zomato
run food_cancel      "in.swiggy.android="                        "Order a Veg Thali from Hotel Sai Thali on Swiggy"                               cancel  "" c_cancel
run food_oos         "in.swiggy.android=oos"                     "Order Mutton Biryani from Behrouz Biryani on Swiggy"                            confirm "No, don't order anything else. Stop." c_oos
run food_login       "in.swiggy.android=login"                   "Order a Misal Pav from Hotel Sai Thali on Swiggy"                               confirm "I can't log in right now, stop the order." c_login
run grocery_instamart "in.swiggy.android.instamart=popup"        "On Instamart order 1 litre milk, 6 eggs, 1 kg tomatoes and a white bread"       confirm "" c_insta
run grocery_blinkit  "com.grofers.customer="                     "Order 2 small packs of Maggi and a 750 ml Coca-Cola from Blinkit"              confirm "" c_blinkit
run grocery_zepto_oos "com.zeptoconsumerapp=oos"                 "Order 1 L milk, 12 eggs and 1 kg Tata salt on Zepto"                            confirm "Skip the eggs and order the rest." c_zepto
adb shell pm disable-user --user 0 in.swiggy.android.instamart >/dev/null
run grocery_in_swiggy "in.swiggy.android="                       "Order 1 kg onions and 1 kg potatoes on Instamart"                               confirm "" c_inswig
adb shell pm enable in.swiggy.android.instamart >/dev/null
run ride_uber        "com.ubercab="                              "Book an Uber Go to Nashik Road railway station"                                confirm "" c_uber
run ride_ola_popup   "com.olacabs.customer=popup"                "Book an Ola auto to Nashik Road bus stand"                                     confirm "" c_ola
run ride_rapido      "com.rapido.passenger="                     "Get me a Rapido bike to College Road"                                          confirm "" c_rapido
run shop_amazon      "in.amazon.mShop.android.shopping="         "Buy boAt Airdopes 141 in black on Amazon, budget 1500"                         confirm "" c_amazon
run shop_flipkart    "com.flipkart.android="                     "Order a Milton 1 litre stainless steel water bottle on Flipkart"              confirm "" c_flip
run msg_whatsapp     "com.whatsapp=interstitial"                 "Send a WhatsApp message to 9822012345 saying I'm running late, will reach by 7" confirm "" c_wa
run mail_gmail       "com.google.android.gm="                    "Email hr@example.com asking for one day leave on Monday 10 November"         confirm "" c_mail
run pay_recharge     "com.phonepe.app="                          "Recharge my Jio number 9822012345 with the 349 plan on PhonePe"               confirm "I'll enter the PIN myself — done." c_recharge
run pay_bill         "com.phonepe.app="                          "Pay my MSEDCL electricity bill, consumer number 012345678, on PhonePe"        confirm "I'll enter the PIN myself — done." c_bill
run phone_dnd        ""                                          "Turn on Do not disturb"                                                       confirm "" c_dnd
run phone_reminder   ""                                          "Remind me to drink water at 11:30 pm today"                                   confirm "" c_remind
run phone_alarm      ""                                          "Set an alarm for 6:30 am tomorrow"                                            confirm "" c_alarm
run files_question   ""                                          "What is the bill amount in my electricity bill PDF?"                          confirm "" c_qa
run files_find       ""                                          "Find my sunset photo"                                                         confirm "" c_find
run files_organise   ""                                          "Organise my Download folder by type"                                          confirm "" c_org
run multi_errand     "com.ubercab=;in.swiggy.android="           "Book an Uber Go to College Road and order a Veg Thali from Hotel Sai Thali on Swiggy" confirm "" c_multi 600

echo "" >> $REPORT; echo "**$PASS passed, $FAIL failed** (of $((PASS+FAIL)) run)" >> $REPORT
cat $REPORT
[ $FAIL = 0 ]
