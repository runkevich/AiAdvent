const day5ModelSelect = document.querySelector('#day5-model');
const day5TaskSelect = document.querySelector('#day5-task');
const day5TaskPreview = document.querySelector('#day5-task-preview');
const day5RunButton = document.querySelector('#day5-run');
const day5Notice = document.querySelector('#day5-notice');
const day5Status = document.querySelector('#day5-status');
const day5Result = document.querySelector('#day5-result');
const day5FullRunButton = document.querySelector('#day5-full-run');
const day5FullStatus = document.querySelector('#day5-full-status');
const day5FullProgressWrap = document.querySelector('#day5-full-progress-wrap');
const day5FullProgress = document.querySelector('#day5-full-progress');
const day5FullCurrent = document.querySelector('#day5-full-current');
const day5FullMessage = document.querySelector('#day5-full-message');
const day5FullResults = document.querySelector('#day5-full-results');
const day5AnalyticsStatus = document.querySelector('#day5-analytics-status');
const day5AnalyticsRun = document.querySelector('#day5-analytics-run');
const day5AnalyticsWarning = document.querySelector('#day5-analytics-warning');
const day5AnalyticsContent = document.querySelector('#day5-analytics-content');
const day5HistoryList = document.querySelector('#day5-history-list');
let day5CurrentRunId = null;
let day5History = [];
let day5PollingRunId = null;
let day5Tasks = [];
let day5Busy = false;
let day5FullBusy = false;
let day5Models = [];

function day5Metric(id, value) {
  const node = document.querySelector(`#${id}`);
  if (node) node.textContent = value == null || value === '' ? '—' : String(value);
}

function day5OptionalMetric(id, value) {
  day5Metric(id, value == null || value === '' ? 'Нет данных' : value);
}

function updateDay5Controls() {
  const task = day5Tasks.find((item) => item.id === day5TaskSelect.value);
  day5TaskPreview.textContent = task ? task.text : 'Выберите задачу.';
  day5RunButton.disabled = day5Busy || day5FullBusy || !day5ModelSelect.value || !task;
  day5FullRunButton.disabled = day5FullBusy;
}

function day5StatusLabel(status) {
  return ({ PENDING: 'В очереди', RUNNING: 'Выполняется', COMPLETED: 'Завершён', COMPLETED_WITH_ERRORS: 'Завершён с ошибками', FAILED: 'Остановлен' })[status] || status || 'Неизвестно';
}

function day5FormatNumber(value, digits = 0) {
  return value == null || !Number.isFinite(Number(value))
    ? 'Нет данных'
    : Number(value).toLocaleString('ru-RU', { maximumFractionDigits: digits });
}

function day5FormatCost(value) {
  return value == null ? 'Нет данных' : `$${Number(value).toFixed(8)} USD`;
}

function day5ClearAnalytics(message) {
  day5AnalyticsContent.hidden = true;
  day5AnalyticsWarning.hidden = true;
  day5AnalyticsWarning.textContent = '';
  day5AnalyticsStatus.textContent = 'Загрузка';
  day5AnalyticsRun.textContent = message;
  for (const id of ['day5-model-rows', 'day5-task-rows', 'day5-cost-cards', 'day5-performance-cards', 'day5-conclusions']) {
    document.querySelector(`#${id}`).replaceChildren();
  }
  for (const id of ['day5-chart-cost', 'day5-chart-latency', 'day5-chart-accuracy']) {
    document.querySelector(`#${id}`).replaceChildren();
  }
}

function renderDay5History(runs) {
  day5History = Array.isArray(runs) ? runs : [];
  day5HistoryList.replaceChildren();
  if (!day5History.length) {
    const empty = document.createElement('p');
    empty.textContent = 'Сохранённых полных запусков пока нет.';
    day5HistoryList.append(empty);
    return;
  }
  day5History.forEach((run) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'day5-history-item';
    button.disabled = day5FullBusy;
    button.setAttribute('aria-current', run.runId === day5CurrentRunId ? 'true' : 'false');
    const date = run.createdAt ? new Date(run.createdAt).toLocaleString('ru-RU') : 'Дата неизвестна';
    button.textContent = `${date} · ${day5StatusLabel(run.status)} · ${run.completed}/${run.total} · ${run.runId}`;
    button.addEventListener('click', () => selectDay5Run(run.runId));
    day5HistoryList.append(button);
  });
}

async function loadDay5History() {
  const response = await fetch('/api/day5/runs', { cache: 'no-store' });
  const runs = await readDay5Json(response, 'История запусков');
  renderDay5History(runs);
  return day5History;
}

async function selectDay5Run(runId) {
  day5CurrentRunId = runId;
  renderDay5History(day5History);
  day5ClearAnalytics(`Запуск ${runId}: загружаю сохранённую аналитику…`);
  try {
    const response = await fetch(`/api/day5/runs/${encodeURIComponent(runId)}`, { cache: 'no-store' });
    const state = await readDay5Json(response, 'Состояние запуска');
    if (day5CurrentRunId === runId) showDay5FullRun(state);
    try {
      await loadDay5Analytics(runId);
    } catch (error) {
      day5AnalyticsStatus.textContent = 'Ошибка аналитики';
      day5AnalyticsRun.textContent = `Run ID: ${runId}. Не удалось получить аналитику: ${error.message}`;
    }
    if (['PENDING', 'RUNNING'].includes(state.status)) {
      day5FullBusy = true;
      updateDay5Controls();
      renderDay5History(day5History);
      await pollDay5Run(runId);
    }
  } catch (error) {
    day5AnalyticsStatus.textContent = 'Ошибка загрузки';
    day5AnalyticsRun.textContent = `Не удалось загрузить запуск ${runId}: ${error.message}`;
  }
}

async function loadDay5Analytics(runId) {
  const response = await fetch(`/api/day5/runs/${encodeURIComponent(runId)}/analytics`, { cache: 'no-store' });
  const analytics = await readDay5Json(response, 'Аналитика запуска');
  if (day5CurrentRunId === runId && analytics.runId === runId) renderDay5Analytics(analytics);
}

function renderDay5Analytics(analytics) {
  day5AnalyticsContent.hidden = false;
  day5AnalyticsStatus.textContent = day5StatusLabel(analytics.runStatus);
  day5AnalyticsRun.textContent = `Run ID: ${analytics.runId} · завершено ${analytics.completed} из ${analytics.total}`;
  day5AnalyticsWarning.hidden = analytics.isComplete && analytics.runStatus === 'COMPLETED';
  if (!analytics.isComplete) {
    day5AnalyticsWarning.textContent = `Данные промежуточные (${analytics.completed}/${analytics.total}). Окончательные выводы появятся после завершения эксперимента.`;
  } else if (analytics.runStatus !== 'COMPLETED') {
    day5AnalyticsWarning.textContent = `Запросы завершены со статусом «${day5StatusLabel(analytics.runStatus)}». Метрики, для которых не хватает данных, помечены отдельно; выводы ограничены доступными результатами.`;
  }

  const modelRows = document.querySelector('#day5-model-rows');
  modelRows.replaceChildren();
  analytics.modelSummaries.forEach((model) => {
    const row = document.createElement('tr');
    const cells = [
      `${model.displayName} · ${model.modelId}\nОшибки: ${model.failedRequests}; API: ${model.apiErrors}; нужна проверка: ${model.needsReview}`,
      `${model.correct} / ${model.expectedRequests}`,
      model.accuracyPercent == null ? 'Недостаточно оценённых ответов' : `${day5FormatNumber(model.accuracyPercent, 1)}%`,
      model.averageLatencyMs == null ? 'Нет данных' : `${day5FormatNumber(model.averageLatencyMs, 1)} мс`,
      day5FormatNumber(model.totalTokens),
      day5FormatCost(model.totalCostUsd),
    ];
    cells.forEach((value) => {
      const cell = document.createElement('td');
      cell.textContent = value;
      row.append(cell);
    });
    modelRows.append(row);
  });

  const taskRows = document.querySelector('#day5-task-rows');
  taskRows.replaceChildren();
  const modelIds = ['gpt-6-luna', 'gpt-6.1-sol', 'gpt-6-astra'];
  const conclusionLabels = {
    LUNA_SUFFICIENT: 'Luna sufficient',
    LARGER_MODEL_ADVANTAGE: 'Larger model advantage',
    INCONCLUSIVE: 'Inconclusive',
  };
  analytics.taskComparisons.forEach((task) => {
    const row = document.createElement('tr');
    const taskCell = document.createElement('td');
    taskCell.textContent = task.category;
    row.append(taskCell);
    modelIds.forEach((id) => {
      const result = task.models[id];
      const cell = document.createElement('td');
      const answer = document.createElement('div');
      answer.className = 'day5-task-answer';
      answer.textContent = result.answer || result.error || 'Результат ещё не получен';
      const status = document.createElement('strong');
      status.textContent = result.failureType || result.validationStatus || 'Нет результата';
      const metrics = document.createElement('small');
      metrics.textContent = `${day5FormatNumber(result.latencyMs)} мс · ${day5FormatNumber(result.totalTokens)} токенов · ${day5FormatCost(result.estimatedCostUsd)}`;
      cell.append(answer, status, metrics);
      row.append(cell);
    });
    const conclusion = document.createElement('td');
    conclusion.textContent = analytics.isComplete
      ? (conclusionLabels[task.conclusion] || 'Inconclusive')
      : 'Будет определён после завершения';
    row.append(conclusion);
    taskRows.append(row);
  });

  renderDay5MetricCards('#day5-cost-cards', analytics.modelSummaries, (model) => [
    `Всего: ${day5FormatCost(model.totalCostUsd)}`,
    `Среднее за запрос: ${day5FormatCost(model.averageCostPerRequest)}`,
    `Стоимость на правильный ответ: ${day5FormatCost(model.costPerCorrectAnswer)}`,
  ]);
  const multipliers = analytics.costComparisons?.multipliers || {};
  document.querySelector('#day5-cost-multipliers').textContent =
    `Sol / Luna: ${multipliers.solVsLuna == null ? 'нет данных' : `${day5FormatNumber(multipliers.solVsLuna, 2)}×`} · Astra / Luna: ${multipliers.astraVsLuna == null ? 'нет данных' : `${day5FormatNumber(multipliers.astraVsLuna, 2)}×`}`;
  renderDay5MetricCards('#day5-performance-cards', analytics.modelSummaries, (model) => [
    `Задержка: средняя ${model.averageLatencyMs == null ? 'нет данных' : `${day5FormatNumber(model.averageLatencyMs, 1)} мс`}, медиана ${model.medianLatencyMs == null ? 'нет данных' : `${day5FormatNumber(model.medianLatencyMs, 1)} мс`}`,
    `Токены: input ${day5FormatNumber(model.inputTokens)} · cached ${day5FormatNumber(model.cachedInputTokens)} · output ${day5FormatNumber(model.outputTokens)} · total ${day5FormatNumber(model.totalTokens)}`,
    `Reasoning tokens: ${day5FormatNumber(model.reasoningTokens)}`,
    ...(model.notes || []),
  ]);

  renderDay5Bars('#day5-chart-cost', analytics.modelSummaries, 'totalCostUsd', (v) => day5FormatCost(v));
  renderDay5Bars('#day5-chart-latency', analytics.modelSummaries, 'averageLatencyMs', (v) => `${day5FormatNumber(v, 1)} мс`);
  renderDay5Bars('#day5-chart-accuracy', analytics.modelSummaries, 'accuracyPercent', (v) => `${day5FormatNumber(v, 1)}%`, 100);

  const conclusionSection = document.querySelector('#day5-conclusions-section');
  const conclusionList = document.querySelector('#day5-conclusions');
  conclusionList.replaceChildren();
  conclusionSection.hidden = !analytics.isComplete;
  if (analytics.isComplete) {
    (analytics.automaticConclusions || []).forEach((item) => {
      const li = document.createElement('li');
      li.textContent = item.text;
      conclusionList.append(li);
    });
    if (!analytics.automaticConclusions?.length) {
      const li = document.createElement('li');
      li.textContent = 'Для выводов недостаточно достоверных метрик.';
      conclusionList.append(li);
    }
  }
}

function renderDay5MetricCards(selector, models, linesForModel) {
  const container = document.querySelector(selector);
  container.replaceChildren();
  models.forEach((model) => {
    const card = document.createElement('article');
    card.className = 'day5-metric-card';
    const title = document.createElement('strong');
    title.textContent = model.displayName;
    card.append(title);
    linesForModel(model).forEach((line) => {
      const p = document.createElement('p');
      p.textContent = line;
      card.append(p);
    });
    container.append(card);
  });
}

function renderDay5Bars(selector, models, key, format, fixedMax = null) {
  const container = document.querySelector(selector);
  container.replaceChildren();
  const values = models.map((model) => model[key]).filter((value) => value != null && Number.isFinite(Number(value)));
  const max = fixedMax || Math.max(...values, 0);
  models.forEach((model) => {
    const value = model[key];
    const row = document.createElement('div');
    row.className = 'day5-chart-row';
    const label = document.createElement('span');
    label.textContent = model.displayName;
    const track = document.createElement('div');
    track.className = 'day5-chart-track';
    const fill = document.createElement('div');
    fill.className = 'day5-chart-fill';
    fill.style.width = value == null || max <= 0 ? '0%' : `${Math.max(0, Math.min(100, Number(value) / max * 100))}%`;
    track.append(fill);
    const metric = document.createElement('span');
    metric.textContent = value == null ? 'Нет данных' : format(Number(value));
    row.append(label, track, metric);
    container.append(row);
  });
}

function showDay5FullRun(state) {
  day5FullProgressWrap.hidden = false;
  day5FullProgress.value = state.completed || 0;
  day5FullProgress.max = state.total || 15;
  day5FullStatus.textContent = state.status || 'Состояние неизвестно';
  const model = day5Models.find((item) => item.id === state.currentModelId);
  const task = day5Tasks.find((item) => item.id === state.currentTaskId);
  day5FullCurrent.textContent = state.currentModelId
    ? `Сейчас: ${model?.displayName || state.currentModelId} · ${task?.category || state.currentTaskId} (${state.completed + 1}/${state.total})`
    : `${state.completed || 0} из ${state.total || 15} запросов завершено`;
  const finished = ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED'].includes(state.status);
  day5FullBusy = !finished && ['PENDING', 'RUNNING'].includes(state.status);
  day5FullMessage.textContent = state.error || (state.status === 'COMPLETED'
    ? 'Эксперимент завершён. Результаты сохранены по отдельным файлам.'
    : state.status === 'COMPLETED_WITH_ERRORS'
      ? 'Эксперимент завершён с ошибками. Успешные и ошибочные результаты сохранены отдельно.'
      : state.status === 'FAILED' ? 'Эксперимент остановлен. Сохранённые результаты доступны по ссылке.' : 'Запросы выполняются по очереди; повторных попыток не будет.');
  day5FullResults.href = `/api/day5/runs/${encodeURIComponent(state.runId)}/results`;
  day5FullResults.hidden = !finished;
  updateDay5Controls();
}

async function pollDay5Run(runId) {
  if (day5PollingRunId === runId) return;
  day5PollingRunId = runId;
  try {
    while (true) {
      const response = await fetch(`/api/day5/runs/${encodeURIComponent(runId)}`, { cache: 'no-store' });
      const state = await readDay5Json(response, 'Состояние эксперимента');
      if (day5CurrentRunId === runId) showDay5FullRun(state);
      try {
        await loadDay5Analytics(runId);
      } catch (error) {
        if (day5CurrentRunId === runId) {
          day5AnalyticsStatus.textContent = 'Ошибка аналитики';
          day5AnalyticsWarning.hidden = false;
          day5AnalyticsWarning.textContent = `Не удалось обновить аналитику запуска ${runId}: ${error.message}`;
        }
      }
      if (!['PENDING', 'RUNNING'].includes(state.status)) break;
      await new Promise((resolve) => setTimeout(resolve, 1200));
    }
  } finally {
    if (day5PollingRunId === runId) day5PollingRunId = null;
    if (day5CurrentRunId === runId) day5FullBusy = false;
    updateDay5Controls();
  }
  try {
    await loadDay5History();
    renderDay5History(day5History);
  } catch (_) {
    // Keep the finished result visible if refreshing history fails.
  }
}

async function readDay5Json(response, context) {
  const body = await response.text();
  const contentType = response.headers.get('content-type') || '';
  let payload = null;
  if (body.trim()) {
    try {
      payload = JSON.parse(body);
    } catch (_) {
      if (response.ok) {
        throw new Error(`${context}: сервер вернул некорректный JSON (HTTP ${response.status}, Content-Type: ${contentType || 'не указан'}).`);
      }
    }
  }

  if (!response.ok) {
    if (payload?.result) return payload;
    const detail = payload?.error || (body.trim() ? `Ответ сервера: ${body.slice(0, 240)}` : 'Сервер вернул пустое тело ответа.');
    const staleRouteHint = response.status === 404 ? ' Перезапустите локальный Web UI, чтобы загрузить актуальные маршруты.' : '';
    throw new Error(`${context}: HTTP ${response.status}. ${detail}${staleRouteHint}`);
  }
  if (!body.trim()) {
    throw new Error(`${context}: сервер вернул пустой ответ (HTTP ${response.status}, Content-Type: ${contentType || 'не указан'}).`);
  }
  if (!contentType.toLowerCase().includes('application/json')) {
    throw new Error(`${context}: ожидался JSON, получен Content-Type «${contentType || 'не указан'}» (HTTP ${response.status}).`);
  }
  return payload;
}

function showDay5Result(envelope) {
  const result = envelope.result;
  day5Result.hidden = false;
  day5Metric('day5-validation', ({ CORRECT: 'Верно', INCORRECT: 'Неверно', NEEDS_REVIEW: 'Нужна проверка' })[result.validationStatus] || result.validationStatus);
  const validationPill = document.querySelector('#day5-validation');
  validationPill.classList.toggle('result-status-pass', result.validationStatus === 'CORRECT');
  validationPill.classList.toggle('result-status-fail', result.validationStatus === 'INCORRECT');
  day5Metric('day5-requested-model', result.requestedModelId);
  day5OptionalMetric('day5-actual-model', result.actualModelId);
  day5OptionalMetric('day5-response-status', result.responseStatus);
  day5OptionalMetric('day5-latency', result.latencyMs == null ? null : `${result.latencyMs} мс`);
  day5OptionalMetric('day5-input-tokens', result.inputTokens);
  day5OptionalMetric('day5-cached-tokens', result.cachedInputTokens);
  day5OptionalMetric('day5-output-tokens', result.outputTokens);
  day5OptionalMetric('day5-reasoning-tokens', result.reasoningTokens);
  day5OptionalMetric('day5-total-tokens', result.totalTokens);
  day5OptionalMetric('day5-cost', result.estimatedCostUsd == null ? null : `$${Number(result.estimatedCostUsd).toFixed(8)} USD`);
  day5Metric('day5-answer', result.answer);
  day5Metric('day5-explanation', result.explanation);
  day5Metric('day5-prompt', result.prompt);

  const error = document.querySelector('#day5-error');
  const messages = [result.error, result.failureType ? `Тип: ${result.failureType}` : null, result.validationDetails && result.validationStatus === 'NEEDS_REVIEW' ? result.validationDetails : null, envelope.persistenceError]
    .filter(Boolean);
  error.textContent = messages.join(' ');
  error.hidden = messages.length === 0;
  document.querySelector('#day5-result-file').textContent = envelope.resultFile
    ? `Результат сохранён: ${envelope.resultFile} · запуск ${envelope.runId}`
    : envelope.runId ? `Запуск ${envelope.runId}; файл результата сохранить не удалось.` : '';
}

async function loadDay5Config() {
  try {
    const response = await fetch('/api/day5/config', { cache: 'no-store' });
    const config = await readDay5Json(response, 'Настройки Day 5');
    if (!config || typeof config !== 'object' || !Array.isArray(config.models) || !Array.isArray(config.tasks) || config.models.length === 0 || config.tasks.length === 0) {
      throw new Error('Настройки Day 5 получены, но в ответе нет списка моделей или задач.');
    }
    day5Tasks = config.tasks || [];
    day5Models = config.models || [];
    day5ModelSelect.replaceChildren(...(config.models || []).map((model) => {
      const option = document.createElement('option');
      option.value = model.id;
      option.textContent = `${model.displayName} · ${{ SMALL: 'Small', MEDIUM: 'Medium', FLAGSHIP: 'Flagship' }[model.tier] || model.tier}`;
      return option;
    }));
    day5TaskSelect.replaceChildren(...day5Tasks.map((task) => {
      const option = document.createElement('option');
      option.value = task.id;
      option.textContent = task.category;
      return option;
    }));
    day5ModelSelect.disabled = !config.models?.length;
    day5TaskSelect.disabled = !day5Tasks.length;
    day5Status.textContent = `${config.reasoningEffort} · ${config.maxOutputTokens} токенов`;
    day5Notice.textContent = 'Будет отправлен один запрос к выбранной модели. Результат сохранится отдельно.';
    updateDay5Controls();
    try {
      await loadDay5History();
      if (day5History.length) await selectDay5Run(day5History[0].runId);
    } catch (error) {
      day5HistoryList.textContent = `Не удалось загрузить историю запусков: ${error.message}`;
    }
  } catch (error) {
    day5ModelSelect.replaceChildren(new Option('Модели недоступны', ''));
    day5TaskSelect.replaceChildren(new Option('Задачи недоступны', ''));
    day5ModelSelect.disabled = true;
    day5TaskSelect.disabled = true;
    day5TaskPreview.textContent = 'Список задач недоступен, пока настройки Day 5 не загрузятся.';
    day5RunButton.disabled = true;
    day5Status.textContent = 'Ошибка загрузки';
    day5Notice.textContent = `Не удалось загрузить настройки Day 5. ${error.message}`;
  }
}

day5ModelSelect.addEventListener('change', updateDay5Controls);
day5TaskSelect.addEventListener('change', updateDay5Controls);
day5RunButton.addEventListener('click', async () => {
  if (day5Busy || !day5ModelSelect.value || !day5TaskSelect.value) return;
  day5Busy = true;
  updateDay5Controls();
  day5Result.hidden = true;
  day5Status.textContent = 'Выполняется';
  day5Notice.textContent = 'Выполняется один запрос. Повторный запуск временно заблокирован.';
  try {
    const response = await fetch('/api/day5/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ modelId: day5ModelSelect.value, taskId: day5TaskSelect.value }),
    });
    const payload = await readDay5Json(response, 'Результат Day 5');
    if (!response.ok && !payload.result) throw new Error(payload.error || `Локальный сервер вернул HTTP ${response.status}.`);
    showDay5Result(payload);
    day5Status.textContent = payload.persistenceError ? 'Не удалось сохранить' : payload.result.error ? 'Запрос завершился ошибкой' : 'Готово';
    day5Notice.textContent = payload.result.error
      ? 'Запрос завершился ошибкой; ниже показаны доступные данные.'
      : 'Тестовый запрос завершён.';
  } catch (error) {
    day5Status.textContent = 'Ошибка';
    day5Notice.textContent = `Не удалось выполнить тестовый запрос: ${error.message}`;
  } finally {
    day5Busy = false;
    updateDay5Controls();
  }
});

day5FullRunButton.addEventListener('click', async () => {
  if (day5FullBusy) return;
  const approved = window.confirm('Будут выполнены 15 запросов к OpenAI: 3 модели × 5 задач. Запросы могут повлечь расходы по тарифам моделей. Запустить полный эксперимент?');
  if (!approved) return;
  day5FullBusy = true;
  updateDay5Controls();
  day5FullStatus.textContent = 'Запускаю…';
  day5FullProgressWrap.hidden = false;
  day5FullMessage.textContent = 'Подготавливаю полный эксперимент…';
  day5ClearAnalytics('Создаю новый запуск; результаты прошлого запуска остаются в истории.');
  try {
    const response = await fetch('/api/day5/experiment', { method: 'POST' });
    const state = await readDay5Json(response, 'Полный эксперимент');
    day5CurrentRunId = state.runId;
    day5ClearAnalytics(`Запуск ${state.runId}: запросы выполняются, аналитика обновится по его данным.`);
    showDay5FullRun(state);
    try {
      await loadDay5History();
      renderDay5History(day5History);
    } catch (_) {
      // The active run is already keyed by its runId; history can refresh later.
    }
    await pollDay5Run(state.runId);
  } catch (error) {
    day5FullBusy = false;
    day5FullStatus.textContent = 'Ошибка запуска';
    day5FullMessage.textContent = `Не удалось запустить эксперимент: ${error.message}`;
    updateDay5Controls();
    await loadDay5History().catch(() => {});
  }
});

loadDay5Config();
