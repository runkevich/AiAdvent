(async () => {
  const response = await fetch('/day4/day4.html');
  if (!response.ok) throw new Error(`Не удалось загрузить интерфейс Дня 4: HTTP ${response.status}`);
  document.querySelector('#day4-root').innerHTML = await response.text();

const day4Task = document.querySelector('#day4-task');
const day4Temperature = document.querySelector('#day4-temperature');
const day4Repetitions = document.querySelector('#day4-repetitions');
const day4TestButton = document.querySelector('#day4-test-run');
const day4FullButton = document.querySelector('#day4-full-run');
const day4Notice = document.querySelector('#day4-notice');
let day4Tasks = [];
let day4Polling = false;

function day4SetRunning(running) {
  day4TestButton.disabled = running;
  day4FullButton.disabled = running;
}

function day4Format(value, digits = 2) {
  return value == null || Number.isNaN(Number(value)) ? 'нет данных' : Number(value).toFixed(digits);
}

function day4StatusLabel(status) {
  return ({ CORRECT: 'Верно', INCORRECT: 'Неверно', PASS: 'Проверки пройдены', FAIL: 'Есть нарушения', NEEDS_REVIEW: 'Нет данных для проверки' })[status] || status || '—';
}

function day4ShowResults(results = []) {
  const container = document.querySelector('#day4-results');
  container.replaceChildren();
  for (const result of results) {
    const task = day4Tasks.find((item) => item.id === result.task_id);
    const card = document.createElement('article');
    card.className = 'experiment-result day4-result';
    const heading = document.createElement('div');
    heading.className = 'experiment-result-heading';
    const name = task?.id === 'creative' ? 'Creative — Specialty Coffee' : 'Factual — Shopping';
    appendText(heading, 'h4', `${name} · temperature ${result.temperature} · повтор ${result.repetition_number}`);
    const status = appendText(heading, 'span', day4StatusLabel(result.validation_status));
    status.className = result.validation_status === 'CORRECT' || result.validation_status === 'PASS' ? 'result-status-pass' : (result.validation_status === 'INCORRECT' || result.validation_status === 'FAIL' ? 'result-status-fail' : '');
    card.append(heading);

    const metrics = document.createElement('div');
    metrics.className = 'metrics';
    addMetric(metrics, 'HTTP status', result.http_status ?? '—');
    addMetric(metrics, 'Finish reason', result.finish_reason ?? '—');
    addMetric(metrics, 'Latency', result.latency_ms == null ? '—' : `${result.latency_ms} мс`);
    addMetric(metrics, 'Input tokens', result.input_tokens ?? '—');
    addMetric(metrics, 'Cached input tokens', result.cached_input_tokens ?? '—');
    addMetric(metrics, 'Output tokens', result.output_tokens ?? '—');
    addMetric(metrics, 'Total tokens', result.total_tokens ?? '—');
    addMetric(metrics, 'Estimated cost', result.estimated_cost == null ? 'нет данных' : `$${day4Format(result.estimated_cost, 8)} USD`);
    addMetric(metrics, 'Модель из ответа API', result.model ?? '—');
    card.append(metrics);
    if (result.error) appendText(card, 'p', result.error, 'item-error');
    if (result.validation_details) appendText(card, 'p', result.validation_details, 'item-error');
    appendText(card, 'pre', result.response_text || 'Ответ не получен.');
    const details = document.createElement('details');
    details.className = 'raw-response';
    appendText(details, 'summary', 'Сырой ответ API');
    appendText(details, 'pre', result.raw_api_response == null ? 'Сырой ответ отсутствует.' : JSON.stringify(result.raw_api_response, null, 2));
    card.append(details);
    if (result.result_file) appendText(card, 'p', `Сохранено: ${result.result_file}`, 'result-file');
    container.append(card);
  }
}

function day4ShowSummary(summary) {
  const section = document.querySelector('#day4-summary');
  section.hidden = !summary;
  if (!summary) return;
  const metrics = document.querySelector('#day4-summary-metrics');
  metrics.replaceChildren();
  addMetric(metrics, 'Доступные результаты', summary.available_results ?? 0);
  addMetric(metrics, 'Ошибки API', summary.api_error_count ?? 0);
  const tbody = document.querySelector('#day4-comparison-body');
  tbody.replaceChildren();
  for (const row of summary.comparison || []) {
    const tr = document.createElement('tr');
    const values = [row.temperature, row.factual_accuracy == null ? 'нет данных' : `${day4Format(row.factual_accuracy, 1)}%`, row.creative_valid_responses ?? 'нет данных', row.creative_unique_names ?? 'нет данных', row.average_latency_ms == null ? 'нет данных' : `${day4Format(row.average_latency_ms, 0)} мс`, day4Format(row.average_tokens, 1), row.total_cost == null ? 'нет данных' : `$${day4Format(row.total_cost, 8)}`];
    for (const value of values) appendText(tr, 'td', value);
    tbody.append(tr);
  }
  const breakdown = document.querySelector('#day4-breakdown');
  breakdown.replaceChildren();
  for (const row of summary.by_temperature || []) {
    const block = document.createElement('article');
    block.className = 'variant-average';
    appendText(block, 'h5', `Temperature ${row.temperature}`);
    const factual = row.factual || {};
    appendText(block, 'p', `Factual: ${factual.correct_count ?? 0}/${factual.available_results ?? 0} правильных · ${factual.unique_formulations ?? 0} уникальных формулировок · средняя latency ${day4Format(factual.average_latency_ms, 0)} мс · токены ${day4Format(factual.average_tokens, 1)} · средняя стоимость $${day4Format(factual.average_cost, 8)}`);
    const creative = row.creative || {};
    appendText(block, 'p', `Creative: ${creative.valid_responses ?? 0}/${creative.available_results ?? 0} корректных списков · ${creative.unique_names ?? 0} уникальных названий · повторы внутри ответов ${creative.repeats_within_answers ?? 0}, между ответами ${creative.repeats_between_runs ?? 0} · средняя latency ${day4Format(creative.average_latency_ms, 0)} мс · токены ${day4Format(creative.average_tokens, 1)} · средняя стоимость $${day4Format(creative.average_cost, 8)}`);
    breakdown.append(block);
  }
}

function day4ShowState(state) {
  const card = document.querySelector('#day4-run-card');
  card.hidden = false;
  setText('day4-run-status', ({ queued: 'В очереди', running: 'Выполняется', completed: 'Завершён', failed: 'Завершён с ошибкой' })[state.status] || state.status);
  setText('day4-progress-text', `${state.completed_requests}/${state.total_requests} запросов`);
  const progress = card.querySelector('[role="progressbar"]');
  progress.setAttribute('aria-valuemax', String(state.total_requests));
  progress.setAttribute('aria-valuenow', String(state.completed_requests));
  document.querySelector('#day4-progress-fill').style.width = `${state.total_requests ? (state.completed_requests / state.total_requests) * 100 : 0}%`;
  const current = state.current_request;
  setText('day4-current-request', current ? `Сейчас: ${current.task_category}, temperature ${current.temperature}, повтор ${current.repetition_number} (${current.ordinal}/${state.total_requests})` : state.status === 'completed' ? 'Все запросы обработаны.' : 'Подготовка к запуску…');
  const error = document.querySelector('#day4-run-error');
  error.textContent = state.error || '';
  error.hidden = !state.error;
  day4ShowResults(state.results || []);
  day4ShowSummary(state.summary);
  const running = ['queued', 'running'].includes(state.status);
  day4SetRunning(running);
  if (state.status === 'completed') day4Notice.textContent = `Эксперимент завершён. Результаты сохранены в ${state.result_directory}.`;
}

async function day4Poll(runId) {
  if (day4Polling) return;
  day4Polling = true;
  try {
    let state;
    do {
      const response = await fetch(`/api/day4/runs/${encodeURIComponent(runId)}`, { cache: 'no-store' });
      state = await response.json();
      if (!response.ok) throw new Error(state.error || `HTTP ${response.status}`);
      day4ShowState(state);
      if (['queued', 'running'].includes(state.status)) await new Promise((resolve) => setTimeout(resolve, 1000));
    } while (['queued', 'running'].includes(state.status));
    localStorage.removeItem('everyday-ai-lab-day4-run-id');
  } catch (error) {
    day4Notice.textContent = `Не удалось обновить прогресс: ${error.message}`;
    window.setTimeout(() => { day4Polling = false; day4Poll(runId); }, 2500);
    return;
  }
  day4Polling = false;
}

async function day4LoadConfig() {
  try {
    const response = await fetch('/api/day4/config', { cache: 'no-store' });
    const config = await response.json();
    if (!response.ok) throw new Error(config.error || `HTTP ${response.status}`);
    day4Tasks = config.tasks || [];
    setText('day4-model-badge', config.model);
    setText('day4-parameters', `Модель: ${config.model} · top_p = ${config.top_p} · max_completion_tokens = ${config.max_completion_tokens}`);
    day4Task.innerHTML = '';
    for (const item of day4Tasks) {
      const option = document.createElement('option');
      option.value = item.id;
      option.textContent = item.id === 'creative' ? 'Creative — Specialty Coffee' : 'Factual — Shopping';
      day4Task.append(option);
    }
    day4Task.disabled = false;
    day4Temperature.disabled = false;
    day4Repetitions.disabled = false;
    day4UpdatePrompt();
  } catch (error) {
    day4Notice.textContent = `Не удалось загрузить параметры: ${error.message}`;
  }
}

function day4UpdatePrompt() {
  const task = day4Tasks.find((item) => item.id === day4Task.value);
  document.querySelector('#day4-task-preview').textContent = task?.text || '';
}

async function day4ResumeActive() {
  try {
    const response = await fetch('/api/day4/runs/active', { cache: 'no-store' });
    const state = await response.json();
    if (!state?.run_id) return;
    day4ShowState(state);
    if (['queued', 'running'].includes(state.status)) day4Poll(state.run_id);
  } catch (_) { /* controls remain usable if no prior run is available */ }
}

async function day4Start(url, payload) {
  day4SetRunning(true);
  day4Notice.textContent = 'Создаю каталог запуска…';
  try {
    const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    const state = await response.json();
    if (!response.ok) throw new Error(state.error || `HTTP ${response.status}`);
    localStorage.setItem('everyday-ai-lab-day4-run-id', state.run_id);
    day4ShowState(state);
    day4Notice.textContent = 'Запросы выполняются последовательно на сервере. Обновление страницы не остановит запуск.';
    day4Poll(state.run_id);
  } catch (error) {
    day4Notice.textContent = `Не удалось запустить эксперимент: ${error.message}`;
    day4SetRunning(false);
  }
}

day4Task.addEventListener('change', day4UpdatePrompt);
day4TestButton.addEventListener('click', () => day4Start('/api/day4/test', {
  task_id: day4Task.value,
  temperature: Number(day4Temperature.value),
  repetitions: Number(day4Repetitions.value),
}));
day4FullButton.addEventListener('click', () => {
  if (window.confirm('Будет выполнено 30 API-запросов. Они расходуют API-кредиты. Продолжить?')) {
    day4Start('/api/day4/experiment', {});
  }
});
day4LoadConfig();
day4ResumeActive();

})();
