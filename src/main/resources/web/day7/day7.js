const day7IdKey = 'ai-advent-day7-conversation-id';
let day7ConversationId = localStorage.getItem(day7IdKey);
if (!day7ConversationId) {
  day7ConversationId = crypto.randomUUID();
  localStorage.setItem(day7IdKey, day7ConversationId);
}

const day7Transcript = document.querySelector('#day7-transcript');
const day7Message = document.querySelector('#day7-message');
const day7Send = document.querySelector('#day7-send');
const day7Notice = document.querySelector('#day7-notice');

document.querySelector('#day7-new').addEventListener('click', async () => {
  day7ConversationId = crypto.randomUUID();
  localStorage.setItem(day7IdKey, day7ConversationId);
  day7Message.value = '';
  await loadDay7History();
  day7Notice.textContent = 'Начат новый диалог.';
});

day7Send.addEventListener('click', sendDay7Message);
day7Message.addEventListener('keydown', (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') sendDay7Message();
});

async function loadDay7History() {
  document.querySelector('#day7-status').textContent = 'Загружаю историю…';
  try {
    const response = await fetch(`/api/day7/history?conversationId=${encodeURIComponent(day7ConversationId)}`);
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || 'Не удалось загрузить историю.');
    renderDay7Transcript(payload.transcript || []);
    document.querySelector('#day7-status').textContent = payload.transcript?.length ? 'История восстановлена' : 'Новый диалог';
  } catch (error) {
    document.querySelector('#day7-status').textContent = 'Ошибка загрузки';
    day7Notice.textContent = error.message;
  }
}

async function sendDay7Message() {
  const message = day7Message.value.trim();
  if (!message) {
    day7Notice.textContent = 'Сначала введите сообщение.';
    return;
  }
  day7Send.disabled = true;
  day7Notice.textContent = 'Агент отвечает и сохраняет историю…';
  try {
    const response = await fetch('/api/day7/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: day7ConversationId, message }),
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || 'Не удалось выполнить запрос.');
    renderDay7Transcript(payload.transcript || []);
    day7Message.value = '';
    day7Notice.textContent = `Ответ сохранён. В этом ходе вызовов инструментов: ${payload.toolCalls}. Перезапустите приложение и обновите страницу, чтобы проверить восстановление.`;
    document.querySelector('#day7-status').textContent = 'История сохранена';
  } catch (error) {
    day7Notice.textContent = `Ошибка: ${error.message}`;
  } finally {
    day7Send.disabled = false;
  }
}

function renderDay7Transcript(entries) {
  day7Transcript.replaceChildren();
  for (const entry of entries) {
    const article = document.createElement('article');
    article.className = `day7-entry day7-entry-${entry.type}`;
    const label = document.createElement('span');
    label.className = 'day7-entry-label';
    label.textContent = ({ user: 'Вы', assistant: 'Агент', tool_call: `Инструмент · ${entry.toolName || ''}`, tool_result: `Результат · ${entry.toolName || ''}` })[entry.type] || entry.type;
    const text = document.createElement('pre');
    text.textContent = entry.text;
    article.append(label, text);
    if (entry.details) {
      const details = document.createElement('details');
      const summary = document.createElement('summary');
      summary.textContent = 'Аргументы инструмента';
      const args = document.createElement('pre');
      args.textContent = entry.details;
      details.append(summary, args);
      article.append(details);
    }
    day7Transcript.append(article);
  }
  day7Transcript.scrollTop = day7Transcript.scrollHeight;
}

loadDay7History();
