const day6Message = document.querySelector('#day6-message');
const day6Send = document.querySelector('#day6-send');
const day6Notice = document.querySelector('#day6-notice');
const day6Result = document.querySelector('#day6-result');

document.querySelectorAll('.day6-example').forEach((button) => {
  button.addEventListener('click', () => {
    day6Message.value = button.dataset.prompt || '';
    day6Message.focus();
  });
});

day6Send.addEventListener('click', sendDay6Message);
day6Message.addEventListener('keydown', (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') sendDay6Message();
});

async function sendDay6Message() {
  const message = day6Message.value.trim();
  if (!message) {
    day6Notice.textContent = 'Сначала введите сообщение.';
    return;
  }
  day6Send.disabled = true;
  day6Notice.textContent = 'Агент обрабатывает запрос…';
  day6Result.hidden = true;
  try {
    const response = await fetch('/api/day6/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || 'Не удалось выполнить запрос.');
    document.querySelector('#day6-answer').textContent = payload.answer;
    const tools = document.querySelector('#day6-tools');
    tools.replaceChildren();
    for (const [index, call] of payload.toolCalls.entries()) {
      const details = document.createElement('details');
      details.className = 'day6-tool-call';
      const summary = document.createElement('summary');
      summary.textContent = `Инструмент ${index + 1}: ${call.name}`;
      const result = document.createElement('pre');
      result.textContent = `Аргументы: ${call.arguments}\nРезультат: ${call.result}`;
      details.append(summary, result);
      tools.append(details);
    }
    day6Result.hidden = false;
    day6Notice.textContent = payload.toolCalls.length
      ? `Готово. Агент вызвал инструментов: ${payload.toolCalls.length}.`
      : 'Готово. Ответ получен без вызова инструментов.';
  } catch (error) {
    day6Notice.textContent = `Ошибка: ${error.message}`;
  } finally {
    day6Send.disabled = false;
  }
}
