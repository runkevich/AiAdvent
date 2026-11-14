const day8IdKey = 'ai-advent-day8-conversation-id';
let day8ConversationId = localStorage.getItem(day8IdKey);
if (!day8ConversationId) {
  day8ConversationId = crypto.randomUUID();
  localStorage.setItem(day8IdKey, day8ConversationId);
}

const day8Message = document.querySelector('#day8-message');
const day8Send = document.querySelector('#day8-send');
const day8Notice = document.querySelector('#day8-notice');
let day8Config = { modelContextLimit: 1_047_576, demoContextLimit: 8_000 };

document.querySelector('#day8-short-example').addEventListener('click', () => {
  day8Message.value = 'Сколько будет 18 умножить на 24? Ответь кратко.';
  day8Message.focus();
});
document.querySelector('#day8-long-example').addEventListener('click', () => {
  day8Message.value = buildDay8LongScenario();
  day8Message.focus();
  day8Notice.textContent = 'Кейс включает три противоречия в журнале. Отправь его два раза; на следующей отправке должен сработать учебный лимит.';
});
document.querySelector('#day8-new').addEventListener('click', async () => {
  day8ConversationId = crypto.randomUUID();
  localStorage.setItem(day8IdKey, day8ConversationId);
  day8Message.value = '';
  await loadDay8Data();
  day8Notice.textContent = 'Начат новый замер. Под примером есть готовая короткая цепочка сообщений для сравнения.';
});
day8Send.addEventListener('click', sendDay8Message);
day8Message.addEventListener('keydown', (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') sendDay8Message();
});

async function loadDay8Data() {
  document.querySelector('#day8-status').textContent = 'Загружаю историю…';
  try {
    const [configResponse, historyResponse, metricsResponse] = await Promise.all([
      fetch('/api/day8/config'),
      fetch(`/api/day7/history?conversationId=${encodeURIComponent(day8ConversationId)}`),
      fetch(`/api/day8/metrics?conversationId=${encodeURIComponent(day8ConversationId)}`),
    ]);
    day8Config = await readApiJson(configResponse);
    const history = await readApiJson(historyResponse);
    const metrics = await readApiJson(metricsResponse);
    if (!configResponse.ok || !historyResponse.ok || !metricsResponse.ok) throw new Error(history.error || metrics.error || 'Не удалось загрузить данные.');
    document.querySelector('#day8-context-mode option[value="demo"]').textContent = `Учебный лимит · ${day8Config.demoContextLimit.toLocaleString('ru-RU')} токенов`;
    document.querySelector('#day8-context-mode option[value="model"]').textContent = `Предел модели · ${day8Config.modelContextLimit.toLocaleString('ru-RU')} токенов`;
    renderDay8Transcript(history.transcript || []);
    renderDay8Metrics(metrics);
    document.querySelector('#day8-status').textContent = metrics.turnCount ? `${metrics.turnCount} ходов · ${day8Config.model}` : day8Config.model;
  } catch (error) {
    document.querySelector('#day8-status').textContent = 'Ошибка загрузки';
    day8Notice.textContent = error.message;
  }
}

async function sendDay8Message() {
  const message = day8Message.value.trim();
  if (!message) {
    day8Notice.textContent = 'Сначала введите сообщение.';
    return;
  }
  day8Send.disabled = true;
  day8Notice.textContent = 'Считаю токены и выполняю запрос…';
  try {
    const response = await fetch('/api/day8/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: day8ConversationId, message, contextMode: document.querySelector('#day8-context-mode').value }),
    });
    const payload = await readApiJson(response);
    if (payload.transcript) renderDay8Transcript(payload.transcript);
    if (payload.stats) renderDay8Latest(payload.stats);
    await loadDay8Metrics();
    if (!response.ok) {
      day8Notice.textContent = payload.status === 'context_overflow'
        ? `${payload.error} ${payload.stats?.apiCalls === 0 ? 'Запрос остановлен до API.' : 'Запрос отклонён API.'} Неудачный ход записан в таблицу.`
        : `Ошибка: ${payload.error || 'запрос не выполнен'}`;
      return;
    }
    day8Message.value = '';
    day8Notice.textContent = `Ответ получен. API вызван ${payload.stats.apiCalls} раз; статистика сохранена.`;
    document.querySelector('#day8-status').textContent = 'Статистика обновлена';
  } catch (error) {
    day8Notice.textContent = `Ошибка: ${error.message}`;
  } finally {
    day8Send.disabled = false;
  }
}

async function loadDay8Metrics() {
  const response = await fetch(`/api/day8/metrics?conversationId=${encodeURIComponent(day8ConversationId)}`);
  const metrics = await readApiJson(response);
  if (!response.ok) throw new Error(metrics.error || 'Не удалось загрузить статистику.');
  renderDay8Metrics(metrics);
}

function renderDay8Transcript(entries) {
  const target = document.querySelector('#day8-transcript');
  target.replaceChildren();
  for (const entry of entries) {
    const article = document.createElement('article');
    article.className = `day8-entry day8-entry-${entry.type}`;
    const label = document.createElement('span');
    label.className = 'day8-entry-label';
    label.textContent = ({ user: 'Вы', assistant: 'Агент', tool_call: `Инструмент · ${entry.toolName || ''}`, tool_result: `Результат · ${entry.toolName || ''}` })[entry.type] || entry.type;
    const content = document.createElement('pre');
    content.textContent = entry.text;
    article.append(label, content);
    target.append(article);
  }
  target.scrollTop = target.scrollHeight;
}

function renderDay8Latest(stats) {
  const cards = [
    ['Текущая реплика', `${stats.currentPromptTokens} токенов`],
    ['История до запроса', `${stats.historyTokensBefore} · оценка`],
    ['Весь запрос', `${stats.estimatedRequestTokens} · оценка`],
    ['API input', `${stats.apiInputTokens} · факт`],
    ['Cached input', `${stats.cachedInputTokens} · факт`],
    ['API output', `${stats.apiOutputTokens} · факт`],
    ['Всего API', `${stats.totalApiTokens} · факт`],
    ['Ответ текстом', `${stats.finalAnswerTokensEstimate} · оценка`],
    ['Вызовы API', stats.apiCalls],
    ['Стоимость хода', formatUsd(stats.estimatedCostUsd)],
  ];
  const container = document.querySelector('#day8-latest-metrics');
  container.replaceChildren();
  for (const [label, value] of cards) {
    const card = document.createElement('div');
    const name = document.createElement('span');
    name.textContent = label;
    const metric = document.createElement('strong');
    metric.textContent = value;
    card.append(name, metric);
    container.append(card);
  }
}

function renderDay8Metrics(data) {
  const turns = data.turns || [];
  document.querySelector('#day8-total-cost').textContent = `Всего: ${formatUsd(data.totalEstimatedCostUsd || 0)}`;
  const rows = document.querySelector('#day8-rows');
  rows.replaceChildren();
  let cumulative = 0;
  turns.forEach((turn, index) => {
    cumulative += Number(turn.estimatedCostUsd || 0);
    const row = document.createElement('tr');
    const cells = [
      String(index + 1),
      turn.status === 'success' ? 'Успешно' : turn.status === 'context_overflow' ? 'Превышен лимит' : 'Ошибка API',
      turn.currentPromptTokens,
      turn.historyTokensBefore,
      `${turn.estimatedRequestTokens} / ${turn.contextLimitTokens}`,
      `${turn.apiInputTokens} / ${turn.apiOutputTokens} (cache ${turn.cachedInputTokens})`,
      `${formatUsd(turn.estimatedCostUsd)} (Σ ${formatUsd(cumulative)})`,
    ];
    cells.forEach((value) => {
      const cell = document.createElement('td');
      cell.textContent = value;
      row.append(cell);
    });
    rows.append(row);
  });
  drawDay8Chart(turns);
  if (turns.length) renderDay8Latest(turns[turns.length - 1]);
}

function drawDay8Chart(turns) {
  const chart = document.querySelector('#day8-chart');
  chart.replaceChildren();
  if (!turns.length) {
    chart.textContent = 'График появится после первого запроса.';
    return;
  }
  const width = 720;
  const height = 220;
  const pad = { left: 54, right: 18, top: 18, bottom: 35 };
  const maxValue = Math.max(100, ...turns.flatMap((turn) => [turn.estimatedRequestTokens || 0, turn.apiInputTokens || 0]));
  const x = (i) => pad.left + (turns.length === 1 ? (width - pad.left - pad.right) / 2 : i * (width - pad.left - pad.right) / (turns.length - 1));
  const y = (value) => height - pad.bottom - value * (height - pad.top - pad.bottom) / maxValue;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', 'Оценка размера запроса и фактические API input tokens по ходам');
  for (let line = 0; line <= 4; line++) {
    const value = Math.round(maxValue * line / 4);
    const yy = y(value);
    const grid = document.createElementNS(svg.namespaceURI, 'line');
    grid.setAttribute('x1', String(pad.left)); grid.setAttribute('x2', String(width - pad.right));
    grid.setAttribute('y1', String(yy)); grid.setAttribute('y2', String(yy)); grid.setAttribute('class', 'day8-grid-line');
    svg.append(grid);
    const label = document.createElementNS(svg.namespaceURI, 'text');
    label.setAttribute('x', '4'); label.setAttribute('y', String(yy + 4)); label.setAttribute('class', 'day8-axis-label'); label.textContent = value.toLocaleString('ru-RU');
    svg.append(label);
  }
  const addLine = (field, className) => {
    const points = turns.map((turn, index) => `${x(index)},${y(Number(turn[field] || 0))}`).join(' ');
    const polyline = document.createElementNS(svg.namespaceURI, 'polyline');
    polyline.setAttribute('points', points); polyline.setAttribute('class', className);
    svg.append(polyline);
    turns.forEach((turn, index) => {
      const point = document.createElementNS(svg.namespaceURI, 'circle');
      point.setAttribute('cx', String(x(index))); point.setAttribute('cy', String(y(Number(turn[field] || 0)))); point.setAttribute('r', '4'); point.setAttribute('class', className);
      svg.append(point);
    });
  };
  addLine('estimatedRequestTokens', 'day8-estimate-line');
  addLine('apiInputTokens', 'day8-actual-line');
  chart.append(svg);
}

function formatUsd(value) {
  return new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'USD', maximumFractionDigits: 6 }).format(Number(value || 0));
}

function buildDay8LongScenario() {
  const locations = ['главная сцена', 'бар', 'оранжерея', 'входная зона', 'тихий сад', 'кинозал', 'служебный двор', 'галерея'];
  const owners = ['Марта, продюсер', 'Олек, технический директор', 'Ина, координатор артистов', 'Лена, доступность и сервис', 'Павел, финансы', 'Зося, волонтёры'];
  const tasks = [
    'проверить договор поставщика и закрепить окно доставки до открытия дверей',
    'сверить фактическую вместимость с планом эвакуации и доступным проходом',
    'подтвердить смену команды, перерывы и резервного сотрудника на вечер',
    'собрать три предложения по аренде и отдельно указать монтаж и демонтаж',
    'проверить меню: не менее 60 процентов блюд вегетарианские, маркировка аллергенов видна',
    'согласовать план дождя, навигацию для гостей и тихую зону отдыха',
    'обновить прогноз расходов и сверить его с утверждённым бюджетом',
    'проверить лимит звука, график саундчека и окончание программы',
    'проверить возвратный инвентарь и вывоз отходов без одноразового пластика',
    'подготовить сообщение гостям с транспортом, входом и правилами возврата билета',
    'зафиксировать зависимость от электрика, запасного кабеля и резервного питания',
    'сверить продажи билетов, гостевой список и число мест для волонтёров',
  ];
  const log = Array.from({ length: 40 }, (_, index) => {
    const number = index + 1;
    const date = ['12 июня', '13 июня', '14 июня'][index % 3];
    const location = locations[index % locations.length];
    const owner = owners[index % owners.length];
    const task = tasks[index % tasks.length];
    const deadline = `${9 + (index * 3) % 12}:00`;
    const quote = 240 + ((index * 173) % 2_400);
    const status = ['ожидает подтверждения', 'в работе', 'проверено, нужна подпись', 'готово с оговоркой'][index % 4];
    if (number === 18) return `${number}. ${date}, ${location}; ${owner}: гостевой список сейчас содержит 460 посетителей на один день. План эвакуации и исходный лимит площадки разрешают максимум 420 посетителей. Статус: ${status}; до ${deadline} нужно сократить список или согласовать другую площадку.`;
    if (number === 28) return `${number}. ${date}, ${location}; ${owner}: добавочная аренда света стоит 7 800 PLN, после неё прогноз всего проекта вырос до 74 600 PLN. Утверждённый верхний предел бюджета — 72 000 PLN. Статус: ${status}; нельзя подтверждать заказ без сокращения других расходов.`;
    if (number === 38) return `${number}. ${date}, главная сцена; ${owner}: саундчек с усилителями поставлен на 22:30. Правило площадки запрещает усиленный звук после 22:00. Статус: ${status}; перенести проверку на дневное окно.`;
    return `${number}. ${date}, ${location}; ${owner}: ${task}. Предложенная сумма ${quote} PLN, крайний срок ${deadline}, статус «${status}». Если задача задержится, сообщить Марте до следующего общего брифинга и указать влияние на гостей, бюджет или безопасность.`;
  });

  return [
    'Ты помощник продюсера городского фестиваля «Ночная оранжерея» в Варшаве. Это рабочий пакет проекта; проверь журнал на противоречия с исходными правилами. Не придумывай новые факты.',
    'Исходные правила: фестиваль проходит 12–14 июня на крытой площадке бывшей оранжереи в Праге; максимум 420 посетителей в день; общий бюджет не выше 72 000 PLN; усиленный звук заканчивается до 22:00. Не менее 60% меню — вегетарианское, одноразовый пластик запрещён, маршрут доступен для кресел-колясок. Билет на день стоит 129 PLN, абонемент на три дня — 279 PLN. При дожде гости остаются внутри, поэтому лимит вместимости нельзя повышать.',
    'Команда: Марта отвечает за итоговые решения, Олек — за сцену и электричество, Ина — за артистов, Лена — за доступность и питание, Павел — за бюджет, Зося — за волонтёров. Не подтверждать расходы выше лимита и не обещать гостям детали, которых нет в журнале.',
    'Журнал решений и задач:',
    ...log,
    'Задача: найди ровно три пункта, которые нарушают исходные правила. Для каждого назови номер записи, факт и правило, с которым он конфликтует. Если одного конфликта не найдёшь, напиши об этом. Ответь коротко, без пересказа всего журнала.',
  ].join('\n\n');
}

async function readApiJson(response) {
  const body = await response.text();
  try {
    return JSON.parse(body);
  } catch {
    if (response.status === 404 || !body.trim()) {
      throw new Error(`Сервер вернул пустой ответ (HTTP ${response.status}). Перезапустите приложение командой ./gradlew runWeb.`);
    }
    throw new Error(`Сервер вернул не JSON (HTTP ${response.status}). Проверьте журнал приложения.`);
  }
}

loadDay8Data();
