const day11Key = 'ai-advent-day11-session';
let day11Session = localStorage.getItem(day11Key);
const $11 = (name) => document.getElementById(`day11-${name}`);
const layerNames = { short_term: 'краткосрочная', working: 'рабочая', long_term: 'долговременная' };

$11('session').addEventListener('click', createDay11Session);
$11('remember').addEventListener('click', rememberDay11);
$11('send').addEventListener('click', sendDay11Message);
$11('check').addEventListener('click', checkDay11Memory);
document.querySelectorAll('.day11-clear').forEach((button) => button.addEventListener('click', () => clearDay11Layer(button.dataset.layer)));

function day11Day10Id() {
  try { const checkpoint = JSON.parse(localStorage.getItem('ai-advent-day10-checkpoint') || 'null'); return checkpoint?.a || null; } catch (_) { return null; }
}
async function day11Request(path, body) {
  const response = await fetch(path, { method: body ? 'POST' : 'GET', headers: body ? { 'Content-Type': 'application/json' } : undefined, body: body ? JSON.stringify(body) : undefined });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}
function renderDay11(data) {
  day11Session = data.sessionId; localStorage.setItem(day11Key, day11Session);
  $11('layers').hidden = false; $11('check').disabled = false; $11('status').textContent = 'Память активна';
  $11('short').textContent = JSON.stringify(data.shortTerm || [], null, 2) || 'Пусто';
  $11('working').textContent = JSON.stringify(data.working || {}, null, 2) || 'Пусто';
  $11('long').textContent = JSON.stringify(data.longTerm || {}, null, 2) || 'Пусто';
  $11('short-tokens').textContent = `${data.tokens?.shortTerm ?? 0} токенов`;
  $11('working-tokens').textContent = `${data.tokens?.working ?? 0} токенов`;
  $11('long-tokens').textContent = `${data.tokens?.longTerm ?? 0} токенов`;
}
async function createDay11Session() {
  $11('notice').textContent = 'Создаю три изолированных слоя памяти…';
  try { const data = await day11Request('/api/day11/session', { day10ConversationId: day11Day10Id() }); renderDay11(data); $11('notice').textContent = data.importedDay10Facts ? 'Импортированы Sticky Facts из Дня 10.' : 'Загружен учебный набор facts из сценария Дня 10.'; }
  catch (error) { $11('notice').textContent = `Ошибка: ${error.message}`; }
}
async function loadDay11State() {
  if (!day11Session) return;
  try { renderDay11(await day11Request(`/api/day11/state?sessionId=${encodeURIComponent(day11Session)}`)); } catch (_) { localStorage.removeItem(day11Key); day11Session = null; }
}
async function rememberDay11() {
  const text = $11('memory-text').value.trim(); const layer = $11('layer-select').value;
  if (!day11Session || !text) { $11('notice').textContent = 'Создайте сессию и введите запись.'; return; }
  try { renderDay11(await day11Request('/api/day11/remember', { sessionId: day11Session, layer, text })); $11('memory-text').value = ''; $11('notice').textContent = `Запись явно сохранена в слой «${layerNames[layer]}».`; }
  catch (error) { $11('notice').textContent = `Ошибка: ${error.message}`; }
}
async function clearDay11Layer(layer) {
  if (!day11Session) return;
  try { renderDay11(await day11Request('/api/day11/clear', { sessionId: day11Session, layer })); $11('notice').textContent = `Слой «${layerNames[layer]}» очищен. Повторите контрольные вопросы и зафиксируйте «не помню».`; }
  catch (error) { $11('notice').textContent = `Ошибка: ${error.message}`; }
}
async function sendDay11Message() {
  const message = $11('message').value.trim(); if (!day11Session || !message) { $11('notice').textContent = 'Создайте сессию и введите сообщение.'; return; }
  $11('send').disabled = true;
  try { const data = await day11Request('/api/day11/chat', { sessionId: day11Session, message }); $11('answer').hidden = false; $11('answer-text').textContent = data.answer; $11('answer-stats').textContent = `Слои в prompt: краткосрочная ${data.tokens.shortTerm}, рабочая ${data.tokens.working}, долговременная ${data.tokens.longTerm}; всего ${data.tokens.total} токенов. API: ${data.apiInputTokens} input / ${data.apiOutputTokens} output.`; $11('message').value = ''; await loadDay11State(); }
  catch (error) { $11('notice').textContent = `Ошибка: ${error.message}`; }
  finally { $11('send').disabled = false; }
}
async function checkDay11Memory() {
  if (!day11Session) return; $11('check').disabled = true; $11('notice').textContent = 'Проверяю три вопроса через модель…';
  try { const data = await day11Request('/api/day11/check', { sessionId: day11Session }); const container = $11('check-results'); container.replaceChildren(); for (const check of data.checks || []) { const item = document.createElement('article'); item.className = 'day11-check-result'; const title = document.createElement('strong'); title.textContent = layerNames[check.layer]; const question = document.createElement('p'); question.textContent = check.question; const answer = document.createElement('p'); answer.textContent = `Ответ: ${check.answer}`; const tokens = document.createElement('p'); tokens.textContent = `Контекст: ${check.contextTokens} токенов`; item.append(title, question, answer, tokens); container.append(item); } $11('checks').hidden = false; $11('notice').textContent = 'Проверка готова. Очистите один слой и запустите её снова.'; }
  catch (error) { $11('notice').textContent = `Ошибка: ${error.message}`; }
  finally { $11('check').disabled = false; }
}
loadDay11State();
