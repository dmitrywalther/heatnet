(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.HeatnetInput = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  // Ищем только границы корневого features. Числа исходного файла не разбираются
  // и не сериализуются заново: JavaScript Number не сохраняет произвольный ID.
  function featuresRange(raw) {
    if (typeof raw !== 'string') throw new Error('Исходный GeoJSON ещё не загружен');
    const stack = [];
    let target = -1, end = -1;
    for (let i = 0; i < raw.length; i++) {
      const c = raw[i];
      if (c === '"') {
        const start = i;
        let closed = false;
        for (i++; i < raw.length; i++) {
          if (raw[i] === '\\') i++;
          else if (raw[i] === '"') { closed = true; break; }
        }
        if (!closed) throw new Error('Незавершённая строка GeoJSON');
        if (stack.length === 1 && stack[0].char === '{') {
          let colon = i + 1;
          while (/\s/.test(raw[colon] || '') && colon < raw.length) colon++;
          if (raw[colon] === ':' && JSON.parse(raw.slice(start, i + 1)) === 'features') {
            if (target !== -1) throw new Error('Повторный корневой массив features');
            target = colon + 1;
            while (/\s/.test(raw[target] || '') && target < raw.length) target++;
            if (raw[target] !== '[') throw new Error('Поле features должно быть массивом');
          }
        }
      } else if (c === '{' || c === '[') {
        stack.push({ char: c, at: i });
      } else if (c === '}' || c === ']') {
        const opening = stack.pop();
        if (!opening || (c === '}' ? opening.char !== '{' : opening.char !== '[')) {
          throw new Error('Незавершённая структура GeoJSON');
        }
        if (opening.at === target) end = i;
      }
    }
    if (stack.length || target < 0 || end < 0) throw new Error('Не найден корневой массив features');
    return { start: target, end };
  }

  function appendFeatures(raw, additions) {
    if (!additions.length) return raw;
    const range = featuresRange(raw);
    const separator = raw.slice(range.start + 1, range.end).trim() ? ',' : '';
    return raw.slice(0, range.end) + separator + additions.map(f => JSON.stringify(f)).join(',')
      + raw.slice(range.end);
  }

  function nextRestrictionId(features) {
    const used = new Set(features.map(f => f.properties && f.properties.id));
    let number = 1;
    while (used.has('ui_restriction_' + number)) number++;
    return 'ui_restriction_' + number;
  }

  return { appendFeatures, nextRestrictionId };
});
