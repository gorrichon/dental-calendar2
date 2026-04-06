// ============================================================
// 東山通歯科クリニック 予約処理ロジック
// ============================================================

// 前ノードからデータ取得
const lineData = $('Extract LINE Data').first().json;
const sessionRows = $('Get Session').all();
const menuRows = $('Get Menu Master').all().map(r => r.json).filter(r => r.menu_code);
const settingsRows = $('Get Settings').all().map(r => r.json).filter(r => r.key);
const reservationRows = $('Get Reservations').all().map(r => r.json).filter(r => r.reservation_id);
const holidayRows = $('Get Holidays').all().map(r => r.json).filter(r => r.date);

// セッション初期化
let session = {
  line_user_id: lineData.line_user_id,
  current_step: 'start',
  selected_menu_code: '',
  selected_date: '',
  selected_time: '',
  candidate_slots_json: '',
  patient_name: '',
  phone: '',
  temp_reservation_id: ''
};

const existingSession = sessionRows.find(r => r.json && r.json.line_user_id === lineData.line_user_id);
if (existingSession) {
  session = { ...session, ...existingSession.json };
}

// フォロー時リセット
if (lineData.message_text === '__follow__') {
  session.current_step = 'start';
}

const message = lineData.message_text;
const step = session.current_step || 'start';

// ============================================================
// ヘルパー関数
// ============================================================

function getSetting(key) {
  const row = settingsRows.find(r => r.key === key);
  if (row) return row.value;
  // 直接カラムとして存在する場合（weekday/saturday行）
  const weekdayRow = settingsRows.find(r => r.key === 'weekday');
  if (weekdayRow && weekdayRow[key] !== undefined) return weekdayRow[key];
  return null;
}

function getHoursForDay(dateStr) {
  const date = new Date(dateStr + 'T00:00:00+09:00');
  const dow = date.getDay();
  const dayType = dow === 6 ? 'saturday' : 'weekday';
  const row = settingsRows.find(r => r.key === dayType);
  if (!row) return null;
  return {
    open_am: row.open_am,
    close_am: row.close_am,
    open_pm: row.open_pm,
    close_pm: row.close_pm
  };
}

function isClosedDay(dateStr) {
  const date = new Date(dateStr + 'T00:00:00+09:00');
  const dow = date.getDay();
  const holiday = holidayRows.find(h => h.date === dateStr);
  if (holiday) {
    const closed = holiday.is_closed;
    if (closed === 'TRUE' || closed === true) return true;
    if (closed === 'FALSE' || closed === false) return false;
  }
  if (dow === 0 || dow === 4) return true;
  return false;
}

function timeToMins(t) {
  const [h, m] = t.split(':').map(Number);
  return h * 60 + m;
}

function minsToTime(m) {
  return `${String(Math.floor(m/60)).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`;
}

function generateSlots(menu, fromDateStr) {
  const slotUnit = parseInt(getSetting('slot_unit_min') || 10);
  const parallelLimit = parseInt(getSetting('default_parallel_limit') || 3);
  const maxCandidates = parseInt(getSetting('max_candidate_count') || 5);
  const duration = parseInt(menu.duration_min);
  const buffer = parseInt(menu.buffer_min);
  const total = duration + buffer;
  const menuParallel = Math.min(parseInt(menu.parallel_limit), parallelLimit);
  const slots = [];
  let checkDate = new Date(fromDateStr + 'T00:00:00+09:00');

  for (let d = 0; d < 30 && slots.length < maxCandidates; d++) {
    const ds = checkDate.toISOString().split('T')[0];
    if (!isClosedDay(ds)) {
      const hours = getHoursForDay(ds);
      if (hours) {
        for (const [open, close] of [[hours.open_am, hours.close_am],[hours.open_pm, hours.close_pm]]) {
          if (!open || !close) continue;
          const openM = timeToMins(open);
          const closeM = timeToMins(close);
          for (let t = openM; t + total <= closeM && slots.length < maxCandidates; t += slotUnit) {
            const startTime = minsToTime(t);
            const endTime = minsToTime(t + duration);
            const startAt = `${ds}T${startTime}:00+09:00`;
            const endAt = `${ds}T${endTime}:00+09:00`;
            const sTs = new Date(startAt).getTime();
            const sEs = new Date(endAt).getTime();
            const overlapping = reservationRows.filter(r => {
              if (r.status !== 'confirmed') return false;
              if (r.menu_code !== menu.menu_code) return false;
              const rS = new Date(r.start_at).getTime();
              const rE = new Date(r.end_at).getTime();
              return sTs < rE && sEs > rS;
            });
            if (overlapping.length < menuParallel) {
              slots.push({ date: ds, time: startTime, start_at: startAt, end_at: endAt });
            }
          }
        }
      }
    }
    checkDate.setDate(checkDate.getDate() + 1);
  }
  return slots;
}

function parseDate(input) {
  const jst = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Tokyo' }));
  const tomorrow = new Date(jst); tomorrow.setDate(jst.getDate() + 1);
  const dayAfter = new Date(jst); dayAfter.setDate(jst.getDate() + 2);

  if (input.includes('明後日')) return dayAfter.toISOString().split('T')[0];
  if (input.includes('明日')) return tomorrow.toISOString().split('T')[0];

  let m = input.match(/(\d{4})[\/-](\d{1,2})[\/-](\d{1,2})/);
  if (m) return `${m[1]}-${m[2].padStart(2,'0')}-${m[3].padStart(2,'0')}`;

  m = input.match(/(\d{1,2})[月\/](\d{1,2})/);
  if (m) {
    const y = jst.getFullYear();
    return `${y}-${m[1].padStart(2,'0')}-${m[2].padStart(2,'0')}`;
  }

  const dayNames = ['日','月','火','水','木','金','土'];
  for (let i = 0; i < dayNames.length; i++) {
    if (input.includes('来週') && input.includes(dayNames[i])) {
      const d = new Date(jst);
      const diff = (i - jst.getDay() + 7) % 7 || 7;
      d.setDate(d.getDate() + diff + 7);
      return d.toISOString().split('T')[0];
    }
    if (input.includes(dayNames[i] + '曜')) {
      const d = new Date(jst);
      const diff = (i - jst.getDay() + 7) % 7 || 7;
      d.setDate(d.getDate() + diff);
      return d.toISOString().split('T')[0];
    }
  }
  return null;
}

function buildMenuText() {
  let text = '【ご予約メニュー】\n\n';
  menuRows.forEach((m, i) => {
    text += `${i + 1}. ${m.menu_name}（${m.duration_min}分）\n`;
  });
  text += '\n番号またはメニュー名を入力してください。';
  return text;
}

function getMenuByInput(input) {
  const num = parseInt(input);
  if (!isNaN(num) && num >= 1 && num <= menuRows.length) return menuRows[num - 1];
  return menuRows.find(m => m.menu_name.includes(input) || input.includes(m.menu_name));
}

// ============================================================
// ステップ処理
// ============================================================

let replyMessage = '';
let nextSession = { ...session };
let action = 'none';
let reservationData = null;

const WEEKDAY_NAMES = ['日曜','月曜','火曜','水曜','木曜','金曜','土曜'];

// START
if (step === 'start') {
  const greeting = settingsRows.find(r => r.line_greeting)?.line_greeting ||
    'こんにちは！東山通歯科クリニックのLINE予約窓口です。';
  replyMessage = greeting + '\n\n' + buildMenuText();
  nextSession.current_step = 'waiting_menu';
}

// WAITING MENU
else if (step === 'waiting_menu') {
  const menu = getMenuByInput(message);
  if (!menu) {
    replyMessage = `「${message}」は見つかりませんでした。\n\n` + buildMenuText();
  } else {
    nextSession.selected_menu_code = menu.menu_code;
    nextSession.current_step = 'waiting_liff';
    const liffUrl = `https://liff.line.me/2009294665-aOdrW1do?treatment=${encodeURIComponent(menu.menu_name)}&userId=${session.line_user_id}`;
    replyMessage = `「${menu.menu_name}」を選択しました。\n\n以下のリンクから予約日時をお選びください👇\n\n${liffUrl}`;
  }
}

// WAITING TIME PREFERENCE
else if (step === 'waiting_time_preference') {
  const dateStr = parseDate(message);
  const menu = menuRows.find(m => m.menu_code === session.selected_menu_code);
  if (!dateStr) {
    replyMessage = '日付が読み取れませんでした。\n例：明日、来週月曜、4/15 の形式でご入力ください。';
  } else if (!menu) {
    replyMessage = 'メニューが見つかりません。最初からやり直してください。\n\n' + buildMenuText();
    nextSession.current_step = 'waiting_menu';
  } else {
    const jstToday = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Tokyo' });
    if (dateStr < jstToday) {
      replyMessage = '過去の日付は指定できません。\n別の日程をご入力ください。';
    } else if (isClosedDay(dateStr)) {
      const d = new Date(dateStr + 'T00:00:00+09:00');
      replyMessage = `${dateStr}（${WEEKDAY_NAMES[d.getDay()]}）は休診日です。\n別の日程をご入力ください。`;
    } else {
      const slots = generateSlots(menu, dateStr);
      if (slots.length === 0) {
        replyMessage = `${dateStr}は空き枠がありません。\n別の日程をご入力ください。`;
      } else {
        nextSession.selected_date = dateStr;
        nextSession.candidate_slots_json = JSON.stringify(slots);
        nextSession.current_step = 'showing_candidates';
        const d = new Date(dateStr + 'T00:00:00+09:00');
        let text = `【空き時間】${dateStr}（${WEEKDAY_NAMES[d.getDay()]}）\n\n`;
        slots.forEach((s, i) => { text += `${i + 1}. ${s.date} ${s.time}〜\n`; });
        text += '\n番号を入力してください。\n「別の日」で日付を変更できます。';
        replyMessage = text;
      }
    }
  }
}

// WAITING LIFF
else if (step === 'waiting_liff') {
  if (message.startsWith('__LIFF_COMPLETE__|')) {
    const parts = message.split('|');
    const dateStr = parts[1];
    const time = parts[2];
    // 日時を保存して名前入力へ
    nextSession.selected_date = dateStr;
    nextSession.selected_time = time;
    // 過去の予約から患者情報を検索
const pastReservation = reservationRows.find(r =>
  r.line_user_id === session.line_user_id && r.patient_name && r.phone
);

if (pastReservation) {
  nextSession.patient_name = pastReservation.patient_name;
  nextSession.phone = pastReservation.phone;
  nextSession.current_step = 'waiting_reuse_confirm';
  replyMessage = `以前ご登録の情報がありました。\n\n👤 ${pastReservation.patient_name}\n📞 ${pastReservation.phone}\n\nこの情報で続けますか？`;
} else {
  nextSession.current_step = 'waiting_name';
  replyMessage = `${dateStr} ${time}〜 で承りました。\n\nお名前をフルネームでご入力ください。\n例：山田 太郎`;
}
  } else {
    const menuForLiff = menuRows.find(m => m.menu_code === session.selected_menu_code);
    const liffUrl = `https://liff.line.me/2009294665-aOdrW1do?treatment=${encodeURIComponent(menuForLiff?.menu_name || '')}&userId=${session.line_user_id}`;
    replyMessage = `予約カレンダーから日時をお選びください👇\n\n${liffUrl}`;
  }
}

// SHOWING CANDIDATES
else if (step === 'showing_candidates') {
  if (message.includes('別') || message.includes('変更') || message === '0') {
    nextSession.current_step = 'waiting_time_preference';
    replyMessage = 'ご希望の日程を再度ご入力ください。\n例：明日、来週月曜、4/15など';
  } else {
    const slots = JSON.parse(session.candidate_slots_json || '[]');
    const num = parseInt(message);
    if (!isNaN(num) && num >= 1 && num <= slots.length) {
      const sel = slots[num - 1];
      nextSession.selected_date = sel.date;
      nextSession.selected_time = sel.time;
      nextSession.current_step = 'waiting_name';
      replyMessage = `${sel.date} ${sel.time}〜 で承りました。\n\nお名前をフルネームでご入力ください。\n例：山田 太郎`;
    } else {
      const slots2 = JSON.parse(session.candidate_slots_json || '[]');
      let text = '番号を入力してください。\n\n';
      slots2.forEach((s, i) => { text += `${i + 1}. ${s.date} ${s.time}〜\n`; });
      text += '\n「別の日」で日付変更';
      replyMessage = text;
    }
  }
}

else if (step === 'waiting_reuse_confirm') {
  if (message === 'OK' || message === 'はい' || message === 'yes' || message === 'YES') {
    // 保存済み情報をそのまま使って確認画面へ
    nextSession.current_step = 'waiting_confirm';
    const menuForConfirm = menuRows.find(m => m.menu_code === session.selected_menu_code);
    replyMessage = `【予約内容のご確認】\n\n` +
      `📅 日時：${nextSession.selected_date} ${nextSession.selected_time}〜\n` +
      `🦷 メニュー：${menuForConfirm?.menu_name || ''}\n` +
      `👤 お名前：${nextSession.patient_name}\n` +
      `📞 電話番号：${nextSession.phone}\n\n` +
      `上記の内容でよろしいですか？`;
  } else {
    // 「戻る」→ 通常の入力フローへ
    nextSession.current_step = 'waiting_name';
    replyMessage = `お名前をフルネームでご入力ください。\n例：山田 太郎`;
  }
}

// WAITING NAME
else if (step === 'waiting_name') {
  if (message.length < 2) {
    replyMessage = 'お名前を正しく入力してください。\n例：山田 太郎';
  } else {
    nextSession.patient_name = message;
    nextSession.current_step = 'waiting_phone';
    replyMessage = `${message} 様\n\nお電話番号をご入力ください。\n例：090-1234-5678`;
  }
}

// WAITING PHONE
else if (step === 'waiting_phone') {
  const digits = message.replace(/[^\d]/g, '');
  if (digits.length < 10) {
    replyMessage = '電話番号を正しく入力してください。\n例：090-1234-5678';
  } else {
    nextSession.phone = message;
    nextSession.current_step = 'waiting_confirm';
    const menu = menuRows.find(m => m.menu_code === session.selected_menu_code);
    replyMessage = `【予約内容のご確認】\n\nメニュー：${menu?.menu_name || ''}\n日時：${session.selected_date} ${session.selected_time}〜\nお名前：${session.patient_name}\nお電話：${message}\n\n「確認」または「OK」で予約確定\n「キャンセル」でやり直し`;
  }
}

// WAITING CONFIRM
else if (step === 'waiting_confirm') {
  const confirmWords = ['確認','ok','OK','はい','よろしく','お願い'];
  const cancelWords = ['キャンセル','やり直し','取消','戻る'];
  if (confirmWords.some(w => message.includes(w))) {
    const completeMsg = settingsRows.find(r => r.line_complete_message)?.line_complete_message ||
      'ご予約ありがとうございます。当日お気をつけてお越しください。';
    const menu = menuRows.find(m => m.menu_code === session.selected_menu_code);
    const slots = JSON.parse(session.candidate_slots_json || '[]');
    const sel = slots.find(s => s.date === session.selected_date && s.time === session.selected_time);
    replyMessage = `✅ 予約が完了しました\n\nメニュー：${menu?.menu_name || ''}\n日時：${session.selected_date} ${session.selected_time}〜\nお名前：${session.patient_name}\nお電話：${session.phone}\n\n${completeMsg}`;
    nextSession.current_step = 'completed';
    action = 'create_reservation';
    reservationData = {
      reservation_id: 'R' + Date.now(),
      clinic_id: 'C001',
      status: 'confirmed',
      line_user_id: session.line_user_id,
      patient_name: session.patient_name,
      phone: session.phone,
      email: '',
      menu_code: session.selected_menu_code,
      start_at: sel?.start_at || `${session.selected_date}T${session.selected_time}:00+09:00`,
      end_at: sel?.end_at || '',
      hold_expires_at: '',
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    };
  } else if (cancelWords.some(w => message.includes(w))) {
    Object.assign(nextSession, {
      current_step: 'start',
      selected_menu_code: '',
      selected_date: '',
      selected_time: '',
      patient_name: '',
      phone: '',
      candidate_slots_json: ''
    });
    replyMessage = 'やり直します。\n\n' + buildMenuText();
    nextSession.current_step = 'waiting_menu';
  } else {
    const menu = menuRows.find(m => m.menu_code === session.selected_menu_code);
    replyMessage = `【予約内容のご確認】\n\nメニュー：${menu?.menu_name || ''}\n日時：${session.selected_date} ${session.selected_time}〜\nお名前：${session.patient_name}\nお電話：${session.phone}\n\n「確認」または「OK」で予約確定\n「キャンセル」でやり直し`;
  }
}

// COMPLETED
else if (step === 'completed') {
  if (message.includes('予約') || message.includes('よやく')) {
    Object.assign(nextSession, {
      current_step: 'waiting_menu',
      selected_menu_code: '',
      selected_date: '',
      selected_time: '',
      candidate_slots_json: ''
    });
    replyMessage = '新しいご予約を承ります。\n\n' + buildMenuText();
  } else {
    replyMessage = '予約は完了しています。\n新しいご予約は「予約」と入力してください。';
  }
}

// FALLBACK
else {
  nextSession.current_step = 'waiting_menu';
  replyMessage = buildMenuText();
}

// メッセージ構造を構築（確認画面はボタン付き）
const isConfirmMsg = replyMessage.includes('【予約内容のご確認】') || replyMessage.includes('この情報で続けますか');
const replyMessages = [{
  type: 'text',
  text: replyMessage,
  ...(isConfirmMsg ? {
    quickReply: {
      items: [
        {type: 'action', action: {type: 'message', label: '✅ OK', text: 'OK'}},
        {type: 'action', action: {type: 'message', label: '🔙 戻る', text: 'キャンセル'}}
      ]
    }
  } : {})
}];

// 送信用JSONボディを事前に作成
const requestBody = JSON.stringify({
  replyToken: lineData.reply_token,
  messages: replyMessages
});

return [{
  json: {
    reply_token: lineData.reply_token,
    reply_message: replyMessage,
    reply_messages: replyMessages,
    request_body: requestBody,
    next_session: nextSession,
    action: action,
    reservation_data: reservationData
  }
}];
