const day13Key = 'ai-advent-day13-task';
let day13Task = localStorage.getItem(day13Key);
let day13ExpectedAction = null;
const $13 = (id) => document.getElementById(`day13-${id}`);
const day13Order = ['PLANNING', 'EXECUTION', 'VALIDATION', 'DONE'];
$13('create-task').addEventListener('click', createDay13Task);
$13('advance').addEventListener('click', advanceDay13);
$13('pause').addEventListener('click', () => pauseDay13('pause'));
$13('resume').addEventListener('click', () => pauseDay13('resume'));
$13('ask-assistant').addEventListener('click', askDay13Assistant);
async function day13Request(path, body, method = 'POST') { const response = await fetch(path, { method, headers: method === 'POST' ? { 'Content-Type': 'application/json' } : undefined, body: method === 'POST' ? JSON.stringify(body) : undefined }); const data = await response.json(); if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`); return data; }
function renderDay13(state) {
  day13Task = state.id; localStorage.setItem(day13Key, state.id); $13('state').hidden = false; $13('assistant-panel').hidden = false; $13('events-panel').hidden = false; $13('status').textContent = state.paused ? 'На паузе' : state.stageLabel;
  $13('stage').textContent = state.stageLabel; $13('step').textContent = state.currentStep; $13('expected').textContent = state.expectedActionLabel || 'нет — задача завершена'; $13('paused').textContent = state.paused ? '· на паузе' : '';
  day13ExpectedAction = state.expectedAction || null; $13('advance').hidden = !state.expectedAction || state.paused; $13('advance').textContent = state.expectedActionLabel ? `Выполнить: ${state.expectedActionLabel}` : 'Задача завершена'; $13('pause').hidden = state.paused || state.stage === 'DONE'; $13('resume').hidden = !state.paused;
  document.querySelectorAll('.day13-flow span').forEach((node) => { const index = day13Order.indexOf(node.dataset.stage); const current = day13Order.indexOf(state.stage); node.classList.toggle('is-current', index === current); node.classList.toggle('is-complete', index < current); });
  const events = $13('events'); events.replaceChildren(); (state.events || []).slice().reverse().forEach((event) => { const item = document.createElement('article'); item.className = 'day13-event'; const type = document.createElement('strong'); type.textContent = event.type; const message = document.createElement('p'); message.textContent = event.message; item.append(type, message); events.append(item); });
}
async function createDay13Task() { const title = $13('title-input').value.trim(); if (!title) return; try { renderDay13(await day13Request('/api/day13/tasks', { title, context: $13('context').value.trim() })); $13('notice').textContent = 'Задача создана в планировании. Реализация пока запрещена.'; } catch (error) { $13('notice').textContent = `Ошибка: ${error.message}`; } }
async function advanceDay13() { if (!day13Task || !day13ExpectedAction) return; $13('advance').disabled = true; try { const state = await day13Request('/api/day13/action', { taskId: day13Task, action: day13ExpectedAction }); renderDay13(state); $13('notice').textContent = 'Разрешённый переход применён после проверки автоматом.'; } catch (error) { $13('notice').textContent = `Переход не выполнен: ${error.message}`; } finally { $13('advance').disabled = false; } }
async function pauseDay13(operation) { if (!day13Task) return; try { const state = await day13Request(`/api/day13/${operation}`, { taskId: day13Task }); renderDay13(state); $13('notice').textContent = operation === 'pause' ? 'Задача поставлена на паузу: этап и шаг сохранены.' : 'Задача продолжена с сохранённого шага.'; } catch (error) { $13('notice').textContent = `Ошибка: ${error.message}`; } }
async function askDay13Assistant() { if (!day13Task) return; const message = $13('assistant-message').value.trim(); if (!message) return; try { const data = await day13Request('/api/day13/assistant', { taskId: day13Task, message }); $13('assistant-answer').textContent = data.message; renderDay13(data.state); $13('notice').textContent = data.transitionApplied ? 'Переход применён.' : 'Ассистент отклонил обход автомата; состояние не изменилось.'; } catch (error) { $13('notice').textContent = `Ошибка: ${error.message}`; } }
async function loadDay13Task() { if (!day13Task) return; try { renderDay13(await day13Request(`/api/day13/tasks/${encodeURIComponent(day13Task)}`, null, 'GET')); } catch (_) { localStorage.removeItem(day13Key); day13Task = null; } }
loadDay13Task();
