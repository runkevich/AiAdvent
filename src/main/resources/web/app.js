const promptField = document.querySelector('#prompt');
const variantField = document.querySelector('#variant');
const runButton = document.querySelector('[data-action="test"]');
const fullButton = document.querySelector('[data-action="full"]');
const notice = document.querySelector('#notice');
const resultSection = document.querySelector('#result');
const fullRunSection = document.querySelector('#experiment-run');

const variantNames = {
  baseline: 'Вариант 1 — Baseline',
  format_instructions: 'Вариант 2 — Format instructions + token limit',
  stop_sequence: 'Вариант 3 — Format instructions + token limit + stop sequence',
  json_schema: 'Вариант 4 — Strict JSON Schema',
};

function setText(id, value) {
  document.querySelector(`#${id}`).textContent = value ?? '—';
}

function setBusy(busy) {
  runButton.disabled = busy;
  fullButton.disabled = busy;
}

function showResult(result, fallbackVariant) {
  resultSection.hidden = false;
  setText('result-title', result.experiment_variant_name || variantNames[fallbackVariant]);
  const status = document.querySelector('#result-status');
  status.textContent = result.error ? 'Ошибка запроса' : 'Запрос завершён';
  status.className = 'status-pill';

  const errorBox = document.querySelector('#result-error');
  const errors = [result.error, result.persistence_error].filter(Boolean);
  errorBox.textContent = errors.join(' ');
  errorBox.hidden = errors.length === 0;

  setText('latency', result.latency_ms == null ? '—' : `${result.latency_ms} мс`);
  setText('input-tokens', result.token_usage?.prompt_tokens ?? '—');
  setText('output-tokens', result.token_usage?.completion_tokens ?? '—');
  setText('total-tokens', result.token_usage?.total_tokens ?? '—');
  setText('finish-reason', result.finish_reason ?? '—');

  const validation = result.validation_result;
  const validationNode = document.querySelector('#validation');
  if (validation?.status === 'pass') {
    validationNode.textContent = `Пройдена — ${validation.message}`;
    validationNode.className = 'result-status-pass';
  } else if (validation?.status === 'fail') {
    validationNode.textContent = `Не пройдена — ${validation.message}`;
    validationNode.className = 'result-status-fail';
  } else if (validation?.status === 'not_run') {
    validationNode.textContent = `Не выполнена — ${validation.message}`;
    validationNode.className = '';
  } else {
    validationNode.textContent = 'Не применяется';
    validationNode.className = '';
  }

  setText('response-text', result.response_text ?? '');
  setText('raw-response', result.raw_api_response == null ? 'Сырой ответ отсутствует.' : JSON.stringify(result.raw_api_response, null, 2));
  const resultFile = result.result_file ? `Сохранено: ${result.result_file}` : '';
  setText('result-file', [resultFile, result.persistence_error].filter(Boolean).join(' · '));
}

function appendText(parent, tag, text, className) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  element.textContent = text ?? '';
  parent.append(element);
  return element;
}

function addMetric(parent, label, value) {
  const item = document.createElement('div');
  appendText(item, 'span', label);
  appendText(item, 'strong', value ?? '—');
  parent.append(item);
}

function showSummary(summary) {
  const section = document.querySelector('#run-summary');
  section.hidden = !summary;
  if (!summary) return;

  const metricGrid = document.querySelector('#summary-metrics');
  metricGrid.replaceChildren();
  addMetric(metricGrid, 'Успешных запросов', `${summary.successful_requests}/${summary.total_requests}`);
  addMetric(metricGrid, 'Ошибок запросов', summary.failed_requests);
  addMetric(metricGrid, 'JSON прошёл проверку', summary.valid_json_responses);

  const variants = document.querySelector('#variant-averages');
  variants.replaceChildren();
  for (const [id, stats] of Object.entries(summary.per_variant || {})) {
    const card = document.createElement('article');
    card.className = 'variant-average';
    appendText(card, 'h5', stats.name || variantNames[id] || id);
    appendText(card, 'p', `Успешно: ${stats.successful_requests}/${stats.request_count}`);
    appendText(card, 'p', `Среднее время: ${formatAverage(stats.average_latency_ms, ' мс')}`);
    appendText(card, 'p', `Средние input/output tokens: ${formatAverage(stats.average_input_tokens)} / ${formatAverage(stats.average_output_tokens)}`);
    variants.append(card);
  }

  const problemSection = document.querySelector('#experiment-problems');
  const problemList = document.querySelector('#problems-list');
  problemList.replaceChildren();
  const problems = summary.problems || [];
  problemSection.hidden = problems.length === 0;
  for (const problem of problems) appendText(problemList, 'li', problem);
}

function formatAverage(value, suffix = '') {
  return value == null ? 'нет данных' : `${value}${suffix}`;
}

function showRunResults(results = []) {
  const container = document.querySelector('#experiment-results');
  container.replaceChildren();
  for (const result of results) {
    const card = document.createElement('article');
    card.className = 'experiment-result';
    const heading = document.createElement('div');
    heading.className = 'experiment-result-heading';
    const title = document.createElement('h4');
    title.textContent = `${result.experiment_variant_name || variantNames[result.experiment_variant] || result.experiment_variant} · запуск ${result.run_number ?? '—'}`;
    heading.append(title);
    appendText(heading, 'span', result.error ? 'Ошибка' : 'Ответ получен');
    card.append(heading);

    const metrics = document.createElement('div');
    metrics.className = 'metrics';
    addMetric(metrics, 'Время', result.latency_ms == null ? '—' : `${result.latency_ms} мс`);
    addMetric(metrics, 'Input tokens', result.token_usage?.prompt_tokens ?? '—');
    addMetric(metrics, 'Output tokens', result.token_usage?.completion_tokens ?? '—');
    addMetric(metrics, 'Total tokens', result.token_usage?.total_tokens ?? '—');
    addMetric(metrics, 'Finish reason', result.finish_reason ?? '—');
    addMetric(metrics, 'JSON', validationLabel(result.validation_result));
    card.append(metrics);

    if (result.error || result.persistence_error) {
      appendText(card, 'p', [result.error, result.persistence_error].filter(Boolean).join(' '), 'item-error');
    }
    if (result.validation_result?.status === 'fail' || result.validation_result?.status === 'not_run') {
      appendText(card, 'p', result.validation_result.message, 'item-error');
    }
    const answer = document.createElement('pre');
    answer.textContent = result.response_text || 'Ответ не получен.';
    card.append(answer);

    const details = document.createElement('details');
    appendText(details, 'summary', 'Сырой ответ API');
    const raw = document.createElement('pre');
    raw.textContent = result.raw_api_response == null ? 'Сырой ответ отсутствует.' : JSON.stringify(result.raw_api_response, null, 2);
    details.append(raw);
    card.append(details);
    if (result.result_file) appendText(card, 'p', `Сохранено: ${result.result_file}`, 'result-file');
    container.append(card);
  }
}

function validationLabel(validation) {
  if (validation?.status === 'pass') return 'Пройдена';
  if (validation?.status === 'fail') return 'Не пройдена';
  if (validation?.status === 'not_run') return 'Не выполнена';
  return 'Не применяется';
}

function showRunState(state) {
  fullRunSection.hidden = false;
  const progress = document.querySelector('#run-progress-text');
  progress.textContent = `${state.completed_requests}/${state.total_requests}`;
  const ratio = state.total_requests ? (state.completed_requests / state.total_requests) * 100 : 0;
  document.querySelector('#progress-fill').style.width = `${ratio}%`;
  const progressBar = document.querySelector('[role="progressbar"]');
  progressBar.setAttribute('aria-valuemax', String(state.total_requests));
  progressBar.setAttribute('aria-valuenow', String(state.completed_requests));

  const labels = { queued: 'В очереди', running: 'Выполняется', completed: 'Завершён', failed: 'Остановлен с ошибкой' };
  setText('run-status', labels[state.status] || state.status);
  const current = state.current_request;
  setText('current-request', current
    ? `Сейчас: ${current.variant_name}, запуск ${current.run_number} (${current.ordinal}/${state.total_requests})`
    : state.status === 'completed' ? 'Все запросы обработаны.' : 'Подготовка к запуску…');
  const error = document.querySelector('#run-error');
  error.textContent = state.error || '';
  error.hidden = !state.error;

  showSummary(state.summary);
  showRunResults(state.results || []);
  const isRunning = state.status === 'queued' || state.status === 'running';
  setBusy(isRunning);
  if (state.status === 'completed') notice.textContent = 'Полный эксперимент завершён. Результаты и сводка доступны ниже.';
  if (state.status === 'failed') notice.textContent = 'Эксперимент остановился из-за внутренней ошибки сервера.';
}

async function pollRun(runId) {
  try {
    const response = await fetch(`/api/day2/runs/${encodeURIComponent(runId)}`, { cache: 'no-store' });
    const state = await response.json();
    if (!response.ok) throw new Error(state.error || `HTTP ${response.status}`);
    showRunState(state);
    if (state.status === 'queued' || state.status === 'running') {
      window.setTimeout(() => pollRun(runId), 1000);
    } else {
      localStorage.removeItem('everyday-ai-lab-run-id');
    }
  } catch (error) {
    notice.textContent = `Не удалось получить прогресс. Сервер продолжает эксперимент, если он ещё выполняется: ${error.message}`;
    window.setTimeout(() => pollRun(runId), 2500);
  }
}

async function resumeActiveRun() {
  try {
    const response = await fetch('/api/day2/runs/active', { cache: 'no-store' });
    if (!response.ok) return;
    const state = await response.json();
    if (!state) return;
    localStorage.setItem('everyday-ai-lab-run-id', state.run_id);
    showRunState(state);
    if (state.status === 'queued' || state.status === 'running') pollRun(state.run_id);
  } catch (_) {
    // The regular page remains usable when there is no previous server-side run.
  }
}

runButton.addEventListener('click', async () => {
  const prompt = promptField.value.trim();
  const variant = variantField.value;
  if (!prompt) {
    notice.textContent = 'Введите базовый prompt перед запуском.';
    promptField.focus();
    return;
  }

  setBusy(true);
  notice.textContent = 'Отправляю один тестовый запрос.';
  resultSection.hidden = true;
  try {
    const response = await fetch('/api/day2/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt, variant }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || `Локальный сервер вернул HTTP ${response.status}.`);
    showResult(result, variant);
    notice.textContent = result.error
      ? 'Запрос завершился ошибкой; результат и доступные данные сохранены.'
      : 'Тестовый запрос завершён.';
  } catch (error) {
    notice.textContent = `Не удалось выполнить тестовый запрос: ${error.message}`;
  } finally {
    setBusy(false);
  }
});

fullButton.addEventListener('click', async () => {
  const prompt = promptField.value.trim();
  if (!prompt) {
    notice.textContent = 'Введите базовый prompt перед запуском.';
    promptField.focus();
    return;
  }
  setBusy(true);
  notice.textContent = 'Сервер запускает 12 запросов последовательно. Можно закрыть страницу: выполнение продолжится.';
  resultSection.hidden = true;
  fullRunSection.hidden = false;
  try {
    const response = await fetch('/api/day2/experiment', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt }),
    });
    const state = await response.json();
    if (!response.ok) throw new Error(state.error || `Локальный сервер вернул HTTP ${response.status}.`);
    localStorage.setItem('everyday-ai-lab-run-id', state.run_id);
    showRunState(state);
    pollRun(state.run_id);
  } catch (error) {
    setBusy(false);
    notice.textContent = `Не удалось запустить эксперимент: ${error.message}`;
  }
});

resumeActiveRun();

const day3TaskSelect = document.querySelector('#day3-task');
const day3TaskPreview = document.querySelector('#day3-task-preview');
const day3MethodSelect = document.querySelector('#day3-method');
const day3ModelBadge = document.querySelector('#day3-model-badge');
const day3RunButton = document.querySelector('#day3-run');
const day3Notice = document.querySelector('#day3-notice');
const day3ResultSection = document.querySelector('#day3-result');
const day3FullRunButton = document.querySelector('#day3-full-run');
const day3RunEstimate = document.querySelector('#day3-run-estimate');
const day3RunNotice = document.querySelector('#day3-run-notice');
const day3RunCard = document.querySelector('#day3-run-card');
let day3Polling = false;
let day3Tasks = [];
const day3MethodNames = {
  direct: 'Direct',
  step_by_step: 'Step-by-step',
  prompt_generation: 'Prompt Generation',
  expert_panel: 'Expert Panel',
};

function day3Text(id, value) {
  const node = document.querySelector(`#${id}`);
  if (node) node.textContent = value ?? '—';
}

function selectedDay3Effort() {
  return document.querySelector('input[name="day3-effort"]:checked')?.value || 'low';
}

function updateDay3TaskPreview() {
  const task = day3Tasks.find((item) => item.id === day3TaskSelect.value);
  day3TaskPreview.textContent = task ? task.text : 'Выберите задачу.';
}

async function loadDay3Config() {
  try {
    const response = await fetch('/api/day3/config', { cache: 'no-store' });
    const config = await response.json();
    if (!response.ok) throw new Error(config.error || `HTTP ${response.status}`);
    day3Tasks = config.tasks || [];
    day3TaskSelect.replaceChildren();
    for (const task of day3Tasks) {
      const option = document.createElement('option');
      option.value = task.id;
      option.textContent = `${task.category} — ${task.text}`;
      day3TaskSelect.append(option);
    }
    day3MethodSelect.replaceChildren();
    for (const method of config.methods || Object.keys(day3MethodNames)) {
      const option = document.createElement('option');
      option.value = method;
      option.textContent = day3MethodNames[method] || method;
      day3MethodSelect.append(option);
    }
    day3ModelBadge.textContent = config.model || 'Модель не указана';
    day3TaskSelect.disabled = day3Tasks.length === 0;
    day3MethodSelect.disabled = false;
    day3RunButton.disabled = day3Tasks.length === 0;
    updateDay3TaskPreview();
    day3Notice.textContent = day3Tasks.length
      ? 'Выполнится один запрос. Полный эксперимент не запускается.'
      : 'Список задач пуст.';
  } catch (_) {
    day3ModelBadge.textContent = 'Нет соединения с сервером';
    day3TaskPreview.textContent = 'Не удалось загрузить список задач.';
    day3Notice.textContent = 'Обновите страницу после запуска локального сервера.';
  }
}

function showDay3Result(result) {
  day3ResultSection.hidden = false;
  const methodName = day3MethodNames[String(result.method || day3MethodSelect.value).toLowerCase()] || result.method || day3MethodSelect.value;
  const heading = result.taskCategory ? `${result.taskCategory} · ${methodName}` : methodName;
  day3Text('day3-result-title', heading);

  const incomplete = result.responseStatus === 'incomplete' || (result.calls || []).some((call) => call.responseStatus === 'incomplete');
  const persistenceError = result.persistenceError || result.persistence_error;
  const failed = Boolean(result.error || persistenceError);
  const status = document.querySelector('#day3-result-status');
  status.textContent = failed ? 'Ошибка' : incomplete ? 'Ответ неполный' : 'Запрос завершён';
  status.className = 'status-pill';

  const messages = [result.error, incomplete ? `Ответ неполный${result.incompleteReason ? `: ${result.incompleteReason}` : '.'}` : null, persistenceError].filter(Boolean);
  const errorBox = document.querySelector('#day3-error');
  errorBox.textContent = messages.join(' ');
  errorBox.hidden = messages.length === 0;

  day3Text('day3-result-effort', (result.reasoningEffort || selectedDay3Effort()).toUpperCase());
  day3Text('day3-result-method', methodName);
  day3Text('day3-result-model', result.model);
  day3Text('day3-http-status', result.apiStatus);
  day3Text('day3-response-status', result.responseStatus);
  day3Text('day3-latency', result.latencyMs == null ? '—' : `${result.latencyMs} мс`);
  day3Text('day3-input-tokens', result.inputTokens);
  day3Text('day3-output-tokens', result.outputTokens);
  day3Text('day3-reasoning-tokens', result.reasoningTokens);
  day3Text('day3-total-tokens', result.totalTokens);
  day3Text('day3-cost', result.estimatedCostUsd == null ? '—' : `$${Number(result.estimatedCostUsd).toFixed(8)} USD`);
  day3Text('day3-response-text', result.answer || 'Ответ не удалось структурировать.');
  day3Text('day3-explanation', result.explanation || 'Краткое объяснение отсутствует.');
  day3Text('day3-expected-answer', result.expectedAnswer ? `Ожидаемый ответ: ${result.expectedAnswer}\n${result.validationDetails || ''}` : (result.validationDetails || ''));
  const validationNode = document.querySelector('#day3-validation');
  const validationLabels = { CORRECT: 'Верно', INCORRECT: 'Неверно', NEEDS_REVIEW: 'Нужна проверка' };
  validationNode.textContent = validationLabels[result.validationStatus] || '—';
  validationNode.className = result.validationStatus === 'CORRECT'
    ? 'result-status-pass'
    : result.validationStatus === 'INCORRECT' ? 'result-status-fail' : '';
  const promptDetails = document.querySelector('#day3-generated-prompt-details');
  promptDetails.hidden = !result.generatedPrompt;
  day3Text('day3-generated-prompt', result.generatedPrompt || '');
  const resultFiles = [...(result.resultFiles || []), result.aggregateResultFile].filter(Boolean);
  day3Text('day3-result-file', resultFiles.length ? `Сохранено: ${resultFiles.join(', ')}` : '');
  day3Text('day3-technical-details', JSON.stringify({
    timestamp: result.timestamp,
    task_id: result.taskId,
    method: result.method,
    reasoning_effort: result.reasoningEffort,
    request_parameters: result.requestParameters,
    response_status: result.responseStatus,
    finish_reason: result.finishReason,
    incomplete_details: result.incompleteDetails,
    calls: result.calls,
  }, null, 2));
}

day3TaskSelect.addEventListener('change', updateDay3TaskPreview);
day3RunButton.addEventListener('click', async () => {
  const taskId = day3TaskSelect.value;
  const reasoningEffort = selectedDay3Effort();
  const method = day3MethodSelect.value;
  day3RunButton.disabled = true;
  day3Notice.textContent = 'Выполняется один запрос к OpenAI…';
  day3ResultSection.hidden = true;
  try {
    const response = await fetch('/api/day3/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ task_id: taskId, reasoning_effort: reasoningEffort, method }),
    });
    const result = await response.json();
    if (!response.ok && result.error && !result.model) throw new Error(result.error);
    showDay3Result(result);
    day3Notice.textContent = result.error
      ? 'Запуск завершился ошибкой. Доступные данные сохранены.'
      : 'Тестовый запрос завершён.';
  } catch (error) {
    day3Notice.textContent = `Не удалось выполнить запрос: ${error.message}`;
  } finally {
    day3RunButton.disabled = day3Tasks.length === 0;
  }
});

const day3Format = (value, digits = 2) => value == null ? '—' : Number(value).toFixed(digits);
const day3Percent = (value) => value == null ? 'недостаточно данных' : `${Number(value) > 0 ? '+' : ''}${value}%`;
const day3StatusName = (value) => ({ CORRECT: 'Верно', INCORRECT: 'Неверно', NEEDS_REVIEW: 'Нужна проверка' }[value] || value || '—');

function renderDay3Summary(summary) {
  if (!summary) return;
  document.querySelector('#day3-run-summary').hidden = false;
  const metricValues = [
    ['Верно', summary.correct_count], ['Неверно', summary.incorrect_count],
    ['Нужна проверка', summary.needs_review_count], ['Ошибки API', summary.api_error_count],
    ['Общая стоимость', summary.total_estimated_cost_usd == null ? '—' : `$${day3Format(summary.total_estimated_cost_usd, 8)}`],
    ['Средняя latency', summary.average_latency_ms == null ? '—' : `${day3Format(summary.average_latency_ms, 0)} мс`],
  ];
  const metrics = document.querySelector('#day3-summary-metrics');
  metrics.replaceChildren(...metricValues.map(([label, value]) => {
    const item = document.createElement('div');
    item.className = 'summary-metric';
    const strong = document.createElement('strong'); strong.textContent = value ?? '0';
    const caption = document.createElement('span'); caption.textContent = label;
    item.append(strong, caption); return item;
  }));

  const methodNames = day3MethodNames;
  const comparison = document.querySelector('#day3-comparison-body');
  comparison.replaceChildren(...(summary.comparison || []).map((row) => {
    const tr = document.createElement('tr');
    const low = row.low || {}; const max = row.max || {};
    [row.method_name || methodNames[String(row.method).toLowerCase()] || row.method,
      low.correct_count ?? 0, max.correct_count ?? 0,
      low.average_latency_ms == null ? '—' : `${day3Format(low.average_latency_ms, 0)} мс`,
      max.average_latency_ms == null ? '—' : `${day3Format(max.average_latency_ms, 0)} мс`,
      low.average_total_tokens ?? '—', max.average_total_tokens ?? '—'].forEach((value) => {
      const td = document.createElement('td'); td.textContent = value; tr.append(td);
    });
    return tr;
  }));
  const observations = summary.observations || {};
  const messages = [
    `Лучшие по числу верных ответов: ${(observations.best_methods || []).join(', ') || 'пока нет данных'}.`,
    `Разница верных ответов Max − Low: ${observations.accuracy_change ?? '—'}.`,
    `Изменение средней latency: ${day3Percent(observations.latency_change_percent)}; токенов: ${day3Percent(observations.token_change_percent)}; reasoning tokens: ${day3Percent(observations.reasoning_token_change_percent)}.`,
    `Различия результата между Low и Max: ${(observations.tasks_with_validation_difference || []).length}.`,
  ];
  const list = document.querySelector('#day3-observations-list');
  list.replaceChildren(...messages.map((message) => { const li = document.createElement('li'); li.textContent = message; return li; }));
}

function renderDay3RunResults(results = []) {
  const body = document.querySelector('#day3-run-results');
  body.replaceChildren(...results.map((item) => {
    const tr = document.createElement('tr');
    [item.taskCategory || item.taskId, day3MethodNames[String(item.method).toLowerCase()] || item.method,
      String(item.reasoningEffort || '').toLowerCase(), item.answer || item.error || '—',
      day3StatusName(item.validationStatus), item.latencyMs == null ? '—' : `${item.latencyMs} мс`,
      `${item.inputTokens ?? '—'} / ${item.outputTokens ?? '—'}`, item.reasoningTokens ?? '—',
      item.estimatedCostUsd == null ? '—' : `$${day3Format(item.estimatedCostUsd, 8)}`].forEach((value) => {
      const td = document.createElement('td'); td.textContent = value; tr.append(td);
    });
    return tr;
  }));
}

function showDay3Run(state) {
  day3RunCard.hidden = false;
  const completed = state.completed_api_calls || 0;
  const total = state.total_api_requests || 80;
  const processed = state.processed_api_requests || completed;
  const progress = total ? Math.min(100, processed / total * 100) : 0;
  document.querySelector('#day3-progress-fill').style.width = `${progress}%`;
  document.querySelector('.day3-run-card .progress-track').setAttribute('aria-valuenow', String(processed));
  document.querySelector('#day3-progress-text').textContent = `${completed}/${total} API-запросов выполнено · ${state.completed_combinations || 0}/${state.total_combinations || 64} решений` + (state.skipped_api_calls ? ` · пропущено ${state.skipped_api_calls}` : '');
  const labels = { queued: 'В очереди', running: 'Выполняется', completed: 'Завершён', failed: 'Ошибка' };
  const status = document.querySelector('#day3-run-status'); status.textContent = labels[state.status] || state.status;
  const current = state.current_combination;
  const apiCall = state.current_api_call;
  document.querySelector('#day3-current-request').textContent = current
    ? `Сейчас: ${current.task_category || current.task_id} · ${day3MethodNames[String(current.method).toLowerCase()] || current.method} · ${String(current.reasoning_effort).toUpperCase()}${apiCall ? ` · запрос ${apiCall.api_call_number}/${total}` : ''}`
    : (state.status === 'completed' ? 'Все комбинации обработаны.' : 'Ожидание следующего запроса…');
  const error = document.querySelector('#day3-run-error'); error.textContent = state.error || ''; error.hidden = !state.error;
  renderDay3RunResults(state.results || []);
  renderDay3Summary(state.summary);
  const files = [state.config_file, state.summary_file].filter(Boolean);
  document.querySelector('#day3-run-files').textContent = files.length ? `Файлы эксперимента: ${files.join(', ')}` : '';
  const active = ['queued', 'running'].includes(state.status);
  day3FullRunButton.disabled = active;
  day3RunButton.disabled = active || day3Tasks.length === 0;
  if (!active) {
    day3RunNotice.textContent = state.status === 'completed' ? 'Эксперимент завершён; результаты сохранены.' : 'Выполнение остановлено. Завершённые результаты сохранены.';
  }
}

async function pollDay3Run(runId) {
  if (day3Polling) return;
  day3Polling = true;
  try {
    let state;
    do {
      const response = await fetch(`/api/day3/runs/${encodeURIComponent(runId)}`, { cache: 'no-store' });
      state = await response.json();
      if (!response.ok) throw new Error(state.error || `HTTP ${response.status}`);
      showDay3Run(state);
      if (['queued', 'running'].includes(state.status)) await new Promise((resolve) => setTimeout(resolve, 1000));
    } while (['queued', 'running'].includes(state.status));
  } catch (error) {
    day3RunNotice.textContent = `Не удалось обновить прогресс: ${error.message}`;
  } finally {
    day3Polling = false;
  }
}

async function loadDay3Estimate() {
  try {
    const response = await fetch('/api/day3/experiment/estimate', { cache: 'no-store' });
    const estimate = await response.json();
    if (!response.ok) throw new Error(estimate.error || `HTTP ${response.status}`);
    const cost = estimate.cost_estimate_usd == null ? 'Стоимость пока нельзя надёжно оценить: не хватает пилотных результатов для всех методов и уровней reasoning.' : `Ориентировочная стоимость: $${day3Format(estimate.cost_estimate_usd, 6)} USD по фактической статистике пилотных запусков.`;
    day3RunEstimate.textContent = `Будет обработано ${estimate.expected_method_results || 64} решений и до ${estimate.expected_api_requests || 80} API-запросов. ${cost}`;
    day3FullRunButton.disabled = false;
  } catch (error) {
    day3RunEstimate.textContent = `Не удалось рассчитать стоимость: ${error.message}`;
  }
}

async function resumeDay3ActiveRun() {
  try {
    const response = await fetch('/api/day3/runs/active', { cache: 'no-store' });
    const state = await response.json();
    if (state && state.run_id) {
      showDay3Run(state);
      if (['queued', 'running'].includes(state.status)) pollDay3Run(state.run_id);
    }
  } catch (_) { /* the single-test UI already reports server connectivity */ }
}

day3FullRunButton.addEventListener('click', async () => {
  const estimateText = day3RunEstimate.textContent;
  const accepted = window.confirm(`Запустить полный эксперимент? Он выполнит 64 решения и до 80 последовательных запросов к ${day3ModelBadge.textContent}. ${estimateText} Результаты сохраняются по ходу выполнения.`);
  if (!accepted) return;
  day3FullRunButton.disabled = true;
  day3RunButton.disabled = true;
  day3RunNotice.textContent = 'Подготавливаю запуск…';
  try {
    const response = await fetch('/api/day3/experiment', { method: 'POST' });
    const state = await response.json();
    if (!response.ok) throw new Error(state.error || `HTTP ${response.status}`);
    showDay3Run(state);
    day3RunNotice.textContent = 'Эксперимент выполняется на сервере. Обновление страницы не остановит запуск.';
    pollDay3Run(state.run_id);
  } catch (error) {
    day3RunNotice.textContent = `Не удалось запустить эксперимент: ${error.message}`;
    day3FullRunButton.disabled = false;
    day3RunButton.disabled = day3Tasks.length === 0;
  }
});

loadDay3Config();
loadDay3Estimate();
resumeDay3ActiveRun();
