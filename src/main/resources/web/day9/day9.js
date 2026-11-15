const day9IdsKey = 'ai-advent-day9-comparison-ids';
let day9Ids = readDay9Ids();
let day9Facts = [];
saveDay9Ids();

const day9Strategy = document.querySelector('#day9-strategy');
const day9Transcript = document.querySelector('#day9-transcript');
const day9Message = document.querySelector('#day9-message');
const day9Send = document.querySelector('#day9-send');
const day9Notice = document.querySelector('#day9-notice');

document.querySelector('#day9-scenario').addEventListener('click', createDay9Scenario);
document.querySelector('#day9-evaluate').addEventListener('click', evaluateDay9Scenario);
day9Strategy.addEventListener('change', loadDay9Conversation);
day9Send.addEventListener('click', sendDay9Message);
day9Message.addEventListener('keydown', (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') sendDay9Message();
});

function readDay9Ids() {
  try {
    const saved = JSON.parse(localStorage.getItem(day9IdsKey) || '{}');
    return { full: saved.full || crypto.randomUUID(), summary: saved.summary || crypto.randomUUID() };
  } catch {
    return { full: crypto.randomUUID(), summary: crypto.randomUUID() };
  }
}

function saveDay9Ids() {
  localStorage.setItem(day9IdsKey, JSON.stringify(day9Ids));
}

function currentDay9Id() {
  return day9Ids[day9Strategy.value];
}

async function createDay9Scenario() {
  const button = document.querySelector('#day9-scenario');
  button.disabled = true;
  day9Notice.textContent = 'Создаю пару одинаковых диалогов для честного сравнения…';
  try {
    const response = await fetch('/api/day9/scenario', { method: 'POST' });
    const payload = await readDay9Json(response);
    if (!response.ok) throw new Error(payload.error || 'Не удалось создать сценарий.');
    day9Ids = { full: payload.fullId, summary: payload.summaryId };
    saveDay9Ids();
    day9Facts = payload.facts || [];
    renderDay9Facts(day9Facts);
    document.querySelector('#day9-evaluate').disabled = false;
    document.querySelector('#day9-evaluation').hidden = true;
    day9Strategy.value = 'full';
    await loadDay9Conversation();
    day9Notice.textContent = `Подготовлен сценарий из ${payload.messageCount} сообщений. В списке ниже — пять деталей для проверки.`;
  } catch (error) {
    day9Notice.textContent = `Ошибка: ${error.message}`;
  } finally {
    button.disabled = false;
  }
}

async function loadDay9Conversation() {
  const id = currentDay9Id();
  document.querySelector('#day9-status').textContent = 'Загружаю…';
  try {
    const [historyResponse, metricsResponse] = await Promise.all([
      fetch(`/api/day9/history?conversationId=${encodeURIComponent(id)}&strategy=${day9Strategy.value}`),
      fetch(`/api/day8/metrics?conversationId=${encodeURIComponent(id)}`),
    ]);
    const [history, metrics] = await Promise.all([readDay9Json(historyResponse), readDay9Json(metricsResponse)]);
    if (!historyResponse.ok || !metricsResponse.ok) throw new Error(history.error || metrics.error || 'Не удалось загрузить историю.');
    renderDay9Transcript(history.transcript || []);
    renderDay9Summary(history.summary || '', history.summarizedTurns || 0, history.summaryApiCalls || 0);
    renderDay9Metrics(metrics, history.summaryTokens || 0, history.summaryApiCalls || 0);
    document.querySelector('#day9-status').textContent = day9Strategy.value === 'summary'
      ? `Summary · сжато ходов: ${history.summarizedTurns || 0}`
      : 'Полная история';
  } catch (error) {
    document.querySelector('#day9-status').textContent = 'Ошибка загрузки';
    day9Notice.textContent = error.message;
  }
}

async function sendDay9Message() {
  const message = day9Message.value.trim();
  if (!message) {
    day9Notice.textContent = 'Сначала введите сообщение.';
    return;
  }
  day9Send.disabled = true;
  day9Notice.textContent = 'Агент отвечает и при необходимости обновляет summary…';
  try {
    const response = await fetch('/api/day9/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: currentDay9Id(), strategy: day9Strategy.value, message }),
    });
    const payload = await readDay9Json(response);
    if (payload.transcript) renderDay9Transcript(payload.transcript);
    if (payload.summary != null) renderDay9Summary(payload.summary, payload.summarizedTurns || 0, payload.summaryApiCalls || 0);
    if (payload.stats) renderDay9Latest(payload.stats, payload.summaryTokens || 0, payload.summaryApiCalls || 0);
    await loadDay9Metrics(payload.summaryTokens || 0, payload.summaryApiCalls || 0);
    if (!response.ok) {
      day9Notice.textContent = payload.error || 'Запрос не выполнен.';
      return;
    }
    day9Message.value = '';
    day9Notice.textContent = `Ответ сохранён. Summary обновлено: ${payload.summaryApiCalls} раз; вызовов инструмента: ${payload.toolCalls}.`;
  } catch (error) {
    day9Notice.textContent = `Ошибка: ${error.message}`;
  } finally {
    day9Send.disabled = false;
  }
}

async function loadDay9Metrics(summaryTokens = 0, summaryApiCalls = 0) {
  const response = await fetch(`/api/day8/metrics?conversationId=${encodeURIComponent(currentDay9Id())}`);
  const metrics = await readDay9Json(response);
  if (!response.ok) throw new Error(metrics.error || 'Не удалось загрузить статистику.');
  renderDay9Metrics(metrics, summaryTokens, summaryApiCalls);
}

async function evaluateDay9Scenario() {
  const button = document.querySelector('#day9-evaluate');
  button.disabled = true;
  button.textContent = 'Проверяю детали…';
  day9Notice.textContent = 'Проверка задаст пять одинаковых вопросов каждой стратегии. Это несколько коротких запросов к модели.';
  try {
    const response = await fetch('/api/day9/evaluate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fullId: day9Ids.full, summaryId: day9Ids.summary }),
    });
    const payload = await readDay9Json(response);
    if (!response.ok) throw new Error(payload.error || 'Не удалось проверить память.');
    renderDay9Evaluation(payload);
    day9Notice.textContent = 'Проверка завершена. Сравните сохранённые детали, входные токены и стоимость.';
  } catch (error) {
    day9Notice.textContent = `Ошибка: ${error.message}`;
  } finally {
    button.disabled = false;
    button.textContent = 'Проверить 5 деталей в обоих режимах';
  }
}

async function readDay9Json(response) {
  const body = await response.text();
  try {
    return JSON.parse(body);
  } catch {
    if (response.status === 404 || !body.trim()) throw new Error(`Сервер вернул пустой ответ (HTTP ${response.status}). Перезапустите приложение командой ./gradlew runWeb.`);
    throw new Error(`Сервер вернул не JSON (HTTP ${response.status}).`);
  }
}

function renderDay9Facts(facts) {
  const container = document.querySelector('#day9-fact-list');
  container.replaceChildren();
  for (const fact of facts) {
    const item = document.createElement('div');
    item.className = 'day9-fact';
    const key = document.createElement('strong');
    key.textContent = fact.key;
    const value = document.createElement('span');
    value.textContent = fact.accepted?.[0] || fact.question;
    item.append(key, value);
    container.append(item);
  }
}

function renderDay9Transcript(entries) {
  day9Transcript.replaceChildren();
  for (const entry of entries) {
    const article = document.createElement('article');
    article.className = `day9-entry day9-entry-${entry.type}`;
    const label = document.createElement('span');
    label.className = 'day9-entry-label';
    label.textContent = entry.type === 'user' ? 'Пользователь' : entry.type === 'assistant' ? 'Ассистент' : entry.type;
    const text = document.createElement('pre');
    text.textContent = entry.text;
    article.append(label, text);
    day9Transcript.append(article);
  }
  day9Transcript.scrollTop = day9Transcript.scrollHeight;
}

function renderDay9Summary(summary, turns, calls) {
  const panel = document.querySelector('#day9-summary');
  panel.hidden = day9Strategy.value !== 'summary';
  document.querySelector('#day9-summary-meta').textContent = `${turns} ходов сжато · обновлений ${calls}`;
  document.querySelector('#day9-summary-text').textContent = summary || 'Summary появится, когда история станет длиннее окна из шести ходов.';
}

function renderDay9Latest(stats, summaryTokens, summaryCalls) {
  const values = [
    ['Текущий prompt', stats.currentPromptTokens],
    ['История до', `${stats.historyTokensBefore} · оценка`],
    ['Запрос к модели', `${stats.estimatedRequestTokens} · оценка`],
    ['API input', stats.apiInputTokens],
    ['API output', stats.apiOutputTokens],
    ['Summary', `${summaryTokens} токенов`],
    ['Вызовы summary', summaryCalls],
    ['Стоимость', formatDay9Usd(stats.estimatedCostUsd)],
  ];
  const target = document.querySelector('#day9-token-summary');
  target.replaceChildren();
  for (const [label, value] of values) {
    const card = document.createElement('div');
    card.innerHTML = '';
    const title = document.createElement('span'); title.textContent = label;
    const metric = document.createElement('strong'); metric.textContent = value;
    card.append(title, metric); target.append(card);
  }
}

function renderDay9Metrics(data, summaryTokens = 0, summaryCalls = 0) {
  const turns = data.turns || [];
  if (turns.length) {
    const latest = turns[turns.length - 1];
    renderDay9Latest(latest, summaryTokens, summaryCalls);
  }
}

function renderDay9Evaluation(payload) {
  const section = document.querySelector('#day9-evaluation');
  section.hidden = false;
  const full = payload.full;
  const summary = payload.summary;
  document.querySelector('#day9-evaluation-summary').textContent =
    `Полная история: ${full.correctCount}/${full.totalDetails} деталей, ${full.stats.apiInputTokens} API input tokens, ${formatDay9Usd(full.stats.estimatedCostUsd)}. ` +
    `Summary: ${summary.correctCount}/${summary.totalDetails} деталей, ${summary.stats.apiInputTokens} API input tokens, ${summary.summaryTokens} токенов в резюме, ${formatDay9Usd(summary.stats.estimatedCostUsd)}.`;
  const grid = document.querySelector('#day9-results-grid');
  grid.replaceChildren();
  for (const result of [full, summary]) {
    const card = document.createElement('article');
    card.className = 'day9-result-card';
    const heading = document.createElement('h4');
    heading.textContent = result.strategy === 'full' ? 'Полная история' : 'Summary + последние ходы';
    const score = document.createElement('p');
    score.textContent = `Сохранено ${result.correctCount} из ${result.totalDetails} деталей`;
    const metrics = document.createElement('p');
    metrics.textContent = `Input ${result.stats.apiInputTokens} · output ${result.stats.apiOutputTokens} · ${formatDay9Usd(result.stats.estimatedCostUsd)}`;
    const list = document.createElement('div');
    for (const answer of result.answers) {
      const row = document.createElement('div');
      row.className = answer.correct ? 'day9-answer-pass' : 'day9-answer-fail';
      row.textContent = `${answer.correct ? '✓' : '×'} ${answer.key}: ${answer.answer}`;
      list.append(row);
    }
    card.append(heading, score, metrics, list); grid.append(card);
  }
}

function formatDay9Usd(value) {
  return new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'USD', maximumFractionDigits: 6 }).format(Number(value || 0));
}

loadDay9Conversation();
