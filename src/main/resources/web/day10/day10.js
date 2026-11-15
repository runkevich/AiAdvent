const day10Key = 'ai-advent-day10-checkpoint';
let day10State = JSON.parse(localStorage.getItem(day10Key) || 'null');
const $10 = (id) => document.getElementById(`day10-${id}`);
const day10Branch = $10('branch');
const day10Strategy = $10('strategy');
function selectedId() { return day10State?.[day10Branch.value]; }
async function jsonResponse(response) {
  const text = await response.text();
  try { return JSON.parse(text); } catch { throw new Error(`Сервер вернул пустой или неверный ответ (HTTP ${response.status}). Перезапустите приложение.`); }
}
document.getElementById('day10-scenario').addEventListener('click', createCheckpoint);
$10('evaluate').addEventListener('click', evaluateStrategies);
$10('send').addEventListener('click', sendMessage);
day10Branch.addEventListener('change', loadBranch);
day10Strategy.addEventListener('change', loadBranch);

async function createCheckpoint() {
  $10('notice').textContent = 'Создаю checkpoint и две ветки…';
  try {
    const data = await jsonResponse(await fetch('/api/day10/scenario', { method: 'POST' }));
    if (!data.branchA) throw new Error(data.error || 'Не удалось создать ветки.');
    day10State = { checkpointId: data.checkpointId, a: data.branchA, b: data.branchB };
    localStorage.setItem(day10Key, JSON.stringify(day10State));
    $10('checkpoint').textContent = `Checkpoint ${data.checkpointId.slice(0, 8)} · создано ${data.messageCount} сообщений · ветки A и B независимы.`;
    $10('evaluate').disabled = false;
    await loadBranch();
    $10('notice').textContent = 'Сценарий готов. Выберите стратегию и ветку.';
  } catch (error) { $10('notice').textContent = `Ошибка: ${error.message}`; }
}
async function loadBranch() {
  if (!selectedId()) return;
  try {
    const data = await jsonResponse(await fetch(`/api/day10/history?conversationId=${encodeURIComponent(selectedId())}`));
    if (data.error) throw new Error(data.error);
    $10('status').textContent = `Ветка ${day10Branch.value.toUpperCase()} · ${day10Strategy.options[day10Strategy.selectedIndex].text}`;
    renderTranscript(data.transcript || []);
    $10('facts').textContent = Object.keys(data.facts || {}).length ? JSON.stringify(data.facts, null, 2) : 'Facts ещё не извлечены. Они обновятся при первом обращении в стратегии Sticky Facts.';
  } catch (error) { $10('notice').textContent = `Ошибка загрузки: ${error.message}`; }
}
async function sendMessage() {
  const message = $10('message').value.trim();
  if (!selectedId() || !message) { $10('notice').textContent = 'Создайте checkpoint и введите сообщение.'; return; }
  $10('send').disabled = true;
  try {
    const data = await jsonResponse(await fetch('/api/day10/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ conversationId: selectedId(), strategy: day10Strategy.value, message }) }));
    if (data.error) throw new Error(data.error);
    $10('message').value = '';
    $10('notice').textContent = `${data.stats.apiInputTokens} input tokens · ${data.stats.apiOutputTokens} output · вызовов модели: ${data.stats.apiCalls} · стоимость ~${Number(data.stats.estimatedCostUsd).toFixed(6)} USD`;
    if (data.facts && Object.keys(data.facts).length) $10('facts').textContent = JSON.stringify(data.facts, null, 2);
    renderTranscript(data.transcript || []);
  } catch (error) { $10('notice').textContent = `Ошибка: ${error.message}`; }
  finally { $10('send').disabled = false; }
}
async function evaluateStrategies() {
  $10('evaluate').disabled = true;
  $10('notice').textContent = 'Проверяю одинаковые пять вопросов для трёх стратегий. Это вызывает модель несколько раз.';
  try {
    const data = await jsonResponse(await fetch('/api/day10/evaluate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ conversationId: selectedId() }) }));
    if (data.error) throw new Error(data.error);
    renderEvaluation(data.results || []);
    $10('notice').textContent = 'Сравнение готово. Измените ветки A/B по очереди, чтобы проверить их независимость.';
  } catch (error) { $10('notice').textContent = `Ошибка: ${error.message}`; }
  finally { $10('evaluate').disabled = false; }
}
function renderTranscript(entries) {
  const container = $10('transcript'); container.replaceChildren();
  for (const entry of entries) {
    const article = document.createElement('article'); article.className = `day9-entry day9-entry-${entry.type}`;
    const label = document.createElement('span'); label.className = 'day9-entry-label'; label.textContent = entry.type === 'user' ? 'Пользователь' : entry.type === 'assistant' ? 'Ассистент' : entry.type;
    const text = document.createElement('pre'); text.textContent = entry.text; article.append(label, text); container.append(article);
  }
}
function renderEvaluation(results) {
  const container = $10('result-cards'); container.replaceChildren(); $10('results').hidden = false;
  const table = $10('table-body'); table.replaceChildren();
  const complexityByStrategy = { sliding_window: 'Низкая', sticky_facts: 'Средняя', summary: 'Средняя / высокая' };
  for (const item of results) {
    const row = document.createElement('tr');
    for (const value of [({ sliding_window: 'Sliding Window', sticky_facts: 'Sticky Facts', summary: 'Summary' })[item.strategy] || item.strategy, `${item.correctCount}/${item.totalDetails}`, `${item.inputTokens} / ${item.outputTokens}`, item.apiCalls, `${Number(item.estimatedCostUsd).toFixed(6)} USD`, complexityByStrategy[item.strategy]]) {
      const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
    }
    table.append(row);
    const card = document.createElement('article'); card.className = 'day9-result-card';
    const title = document.createElement('h4'); title.textContent = ({ sliding_window: 'Sliding Window', sticky_facts: 'Sticky Facts', summary: 'Summary' })[item.strategy] || item.strategy;
    const facts = document.createElement('p'); facts.textContent = `Деталей: ${item.correctCount}/${item.totalDetails}`;
    const metrics = document.createElement('p'); metrics.textContent = `Токены input/output: ${item.inputTokens}/${item.outputTokens} · вызовов: ${item.apiCalls} · ≈${Number(item.estimatedCostUsd).toFixed(6)} USD`;
    const lines = document.createElement('div');
    for (const answer of item.answers || []) { const p = document.createElement('p'); p.textContent = `${answer.correct ? '✓' : '×'} ${answer.key}: ${answer.answer}`; lines.append(p); }
    card.append(title, facts, metrics, lines); container.append(card);
  }
  $10('conclusion').textContent = 'Sliding Window проще и подходит, если важен только недавний контекст. Sticky Facts сохраняют точные даты, суммы и ограничения ценой отдельного извлечения и хранилища. Summary лучше подходит для длинного связного разговора, но требует обновлять резюме моделью.';
}
if (day10State?.a && day10State?.b) { $10('evaluate').disabled = false; $10('checkpoint').textContent = `Восстановлен checkpoint ${day10State.checkpointId.slice(0, 8)}. Можно продолжить любую ветку.`; loadBranch(); }
