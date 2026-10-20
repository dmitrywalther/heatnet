(function (global) {
  'use strict';
  const finite = n => typeof n === 'number' && Number.isFinite(n);
  const fmt = (n, digits = 2) => finite(n) ? n.toLocaleString('ru-RU', {
    minimumFractionDigits: digits, maximumFractionDigits: digits}) : '—';
  const metres = n => fmt(n) + ' м';
  function node(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.setAttribute('class', cls);
    if (text !== undefined) n.textContent = text;
    return n;
  }
  function create(options) {
    const container = options.container;
    container.classList.add('heatnet-connection');
    let segmentButtons = new Map();
    function clear() { container.replaceChildren(); container.hidden = true; segmentButtons = new Map(); }
    function button(text, target) {
      const b = node('button', 'hc-link', text);
      b.setAttribute('type', 'button');
      b.setAttribute('data-connection-target', target.kind);
      b.addEventListener('click', () => options.onTarget?.(target));
      return b;
    }
    function setMessage(text, retry = false) {
      clear(); container.hidden = false;
      const p = node('p', 'hc-message', text); p.setAttribute('role', 'status'); container.appendChild(p);
      if (retry) { const b = node('button', 'ghost', 'Повторить загрузку карточки');
        b.setAttribute('type', 'button'); b.addEventListener('click', () => options.onRetry?.()); container.appendChild(b); }
    }
    function metric(box, label, value) {
      const cell = node('div', 'hc-metric'); cell.appendChild(node('strong', '', value));
      cell.appendChild(node('span', '', label)); box.appendChild(cell);
    }
    function section(title) {
      const details = node('details', 'hc-details'); details.appendChild(node('summary', '', title));
      container.appendChild(details); return details;
    }
    function setData(data) {
      clear(); container.hidden = false;
      const oks = data.oks || {}, connected = data.status === 'connected';
      const head = node('div', 'hc-heading');
      head.appendChild(node('h3', '', 'ОКС ' + (oks.label ?? '—')));
      head.appendChild(node('span', connected ? 'hc-status' : 'hc-status hc-unconnected', connected ? 'Подключён' : 'Не подключён'));
      container.appendChild(head);
      const variant = options.variantLabel?.(data.variant_id) || 'ID варианта: ' + (data.variant_id ?? '—');
      container.appendChild(node('p', 'hc-load', fmt(oks.flow_tph) + ' т/ч · ' + variant));
      if (!connected) {
        container.appendChild(node('p', 'hc-message', data.message || 'В этом варианте подключение отсутствует.'));
        (data.notes || []).filter(text => text !== data.message).forEach(text => container.appendChild(node('p', 'hc-note', text)));
        return;
      }
      const route = data.route_summary || {}, metrics = node('div', 'hc-metrics');
      metric(metrics, 'путь от врезки', metres(route.total_length));
      metric(metrics, 'из них внутри ОКС', metres(route.internal_length));
      metric(metrics, 'общий участок', metres(route.shared_length));
      metric(metrics, 'только этот ОКС', metres(route.dedicated_length));
      metric(metrics, 'из них спецпроходы', metres(route.special_length));
      metric(metrics, 'диаметры на пути', (data.diameters || []).length ? 'ДУ ' + data.diameters.join(' / ') : '—');
      container.appendChild(metrics);
      if (data.source) {
        const source = data.source;
        const kind = source.kind === 'existing_chamber' ? 'существующая камера'
          : source.kind === 'new_chamber_tie' ? 'новая камера на существующей трубе' : 'узел сети';
        container.appendChild(button('Врезка: ' + kind + ' ' + (source.label ?? '—'), {...source, kind:'source'}));
      }
      const entry = data.entry;
      if (entry?.status === 'available') {
        container.appendChild(button('Ввод: ' + (entry.wall_label || 'внешняя стена') + ' · ' + metres(entry.internal_length),
          {kind:'entry', coordinates:entry.coordinates, wall_coordinates:entry.wall_coordinates}));
      } else if (entry?.message) container.appendChild(node('p', 'hc-note', entry.message));
      const explanation = section('Как проходит маршрут');
      explanation.setAttribute('open', '');
      (data.explanations || []).forEach(item => {
        const p = node('p', 'hc-explanation');
        p.appendChild(item.target ? button(item.text, item.target) : node('span', '', item.text));
        explanation.appendChild(p);
      });
      if (!(data.explanations || []).length) explanation.appendChild(node('p', 'hc-note', 'Подробное объяснение для сохранённого результата отсутствует.'));
      const path = section('Участки пути · ' + (data.segments || []).length);
      (data.segments || []).forEach(s => {
        const label = metres(s.to - s.from) + ' · ДУ ' + s.diameter + ' · ' + fmt(s.flow_tph) + ' т/ч'
          + (s.shared ? ' · общий для ' + s.usage_count + ' ОКС' : ' · отдельная ветвь');
        const b = button(label, {kind:'segment', segment_id:s.id});
        b.setAttribute('data-connection-segment', s.id); b.setAttribute('title', s.id);
        path.appendChild(b); segmentButtons.set(String(s.id), b);
      });
      if (data.costs) {
        const costs = section('Стоимость участков пути');
        costs.appendChild(node('p', 'hc-cost', fmt(data.costs.path_segment_cost, 0) + ' ₽'));
        costs.appendChild(node('p', 'hc-note', 'Это не цена подключения: сумма включает общие участки и не включает камеры и врезки.'));
        costs.appendChild(node('p', 'hc-note', 'Общие: ' + fmt(data.costs.shared_segment_cost, 0) + ' ₽ · отдельные: ' + fmt(data.costs.dedicated_segment_cost, 0) + ' ₽.'));
        if (data.costs.note) costs.appendChild(node('p', 'hc-note', data.costs.note));
      }
      if ((data.notes || []).length) {
        const notes = section('О данных карточки');
        data.notes.forEach(text => notes.appendChild(node('p', 'hc-note', text)));
      }
    }
    function highlightSegment(id) {
      segmentButtons.forEach((button, key) => button.classList.toggle('active', id != null && key === String(id)));
    }
    clear(); return {clear, setMessage, setData, highlightSegment};
  }
  global.HeatnetConnectionCard = {create};
})(typeof window !== 'undefined' ? window : globalThis);
