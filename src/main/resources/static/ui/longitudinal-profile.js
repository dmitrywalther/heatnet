(function (global) {
  'use strict';
  const NS = 'http://www.w3.org/2000/svg';
  const VIEW = {width: 1000, height: 258, left: 62, right: 972, top: 43, bottom: 210};
  const finite = n => typeof n === 'number' && Number.isFinite(n);
  const fmt = (n, digits = 2) => finite(n)
    ? n.toLocaleString('ru-RU', {minimumFractionDigits: digits, maximumFractionDigits: digits}) : '—';
  const clamp = (n, a, b) => Math.max(a, Math.min(b, n));
  const types = {road: 'Дорога', tram_tracks: 'Трамвай', tram: 'Трамвай', gas_pipeline: 'Газопровод', gas: 'Газопровод',
    power_cable: 'Кабель', cable: 'Кабель', heat_network: 'Теплосеть', heat_network_crossing: 'Теплосеть',
    metro: 'Метро', water: 'Водоём', sewer: 'Канализация'};

  function html(tag, className, text) {
    const el = document.createElement(tag);
    if (className) el.setAttribute('class', className);
    if (text !== undefined) el.textContent = text;
    return el;
  }
  function svg(tag, attributes, text) {
    const el = document.createElementNS(NS, tag);
    Object.entries(attributes || {}).forEach(([name, value]) => el.setAttribute(name, value));
    if (text !== undefined) el.textContent = text;
    return el;
  }
  function coordinateAt(coordinates, fraction) {
    if (!Array.isArray(coordinates) || !coordinates.length) return null;
    if (coordinates.some(c => !Array.isArray(c) || !finite(c[0]) || !finite(c[1]))) return null;
    if (coordinates.length === 1) return coordinates[0].slice(0, 2);
    const lengths = [], lat = coordinates.reduce((sum, c) => sum + c[1], 0) / coordinates.length;
    const scale = Math.cos(lat * Math.PI / 180);
    let total = 0;
    for (let i = 1; i < coordinates.length; i++) {
      const a = coordinates[i - 1], b = coordinates[i];
      const length = Math.hypot((b[0] - a[0]) * scale, b[1] - a[1]);
      lengths.push(length); total += length;
    }
    let remaining = total * clamp(fraction, 0, 1);
    for (let i = 0; i < lengths.length; i++) {
      if (remaining <= lengths[i] || i === lengths.length - 1) {
        const f = lengths[i] > 0 ? clamp(remaining / lengths[i], 0, 1) : 0;
        const a = coordinates[i], b = coordinates[i + 1];
        return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
      }
      remaining -= lengths[i];
    }
    return coordinates[coordinates.length - 1].slice(0, 2);
  }

  function create(options) {
    if (!options || !options.container) throw new Error('Нужен контейнер продольного профиля.');
    const container = options.container;
    container.classList.add('heatnet-profile');
    let data = null, chart = null, detail = null, cursor = null, dot = null;
    let activeId = null, station = null, segments = new Map(), xScale, yScale;

    function reset() {
      data = null; chart = null; detail = null; cursor = null; dot = null;
      segments = new Map(); activeId = null; station = null;
      container.replaceChildren();
      options.onSegmentHover?.(null);
    }
    function clear() { reset(); container.hidden = true; }
    function shell(title, subtitle) {
      container.hidden = false;
      const head = html('div', 'hp-head');
      const titles = html('div', 'hp-titles');
      titles.appendChild(html('h3', 'hp-title', title));
      if (subtitle) titles.appendChild(html('p', 'hp-subtitle', subtitle));
      head.appendChild(titles);
      const close = html('button', 'hp-close', 'Закрыть');
      close.setAttribute('type', 'button'); close.setAttribute('data-profile-action', 'close');
      close.setAttribute('aria-label', 'Закрыть продольный профиль');
      close.addEventListener('click', () => { clear(); options.onClose?.(); });
      head.appendChild(close); container.appendChild(head);
    }
    function setMessage(text) {
      reset(); shell('Продольный профиль');
      const message = html('p', 'hp-message', text);
      message.setAttribute('role', 'status'); container.appendChild(message);
    }
    function obstacleLabel(o) {
      return o.label || `${types[o.type] || o.type || 'Препятствие'} ${o.id == null ? '' : o.id}`.trim();
    }
    function obstacleText(o) {
      const parts = [obstacleLabel(o), `${fmt(o.from)}–${fmt(o.to)} м от врезки`];
      if (o.role === 'tie_in') parts.push('место врезки в существующую сеть');
      if (finite(o.top_depth)) parts.push(`верх препятствия ${fmt(o.top_depth)} м`);
      if (finite(o.bottom_depth)) parts.push(`низ препятствия ${fmt(o.bottom_depth)} м`);
      if (finite(o.min_top_depth)) parts.push(`минимальная глубина верха трубы ${fmt(o.min_top_depth)} м`);
      if (!finite(o.top_depth) && !finite(o.bottom_depth) && !finite(o.min_top_depth)) parts.push('глубина препятствия не задана');
      if (finite(o.clearance) && o.role !== 'tie_in') parts.push(`требуемый просвет ${fmt(o.clearance)} м`);
      return parts.join(' · ');
    }
    function setActive(id, at, emit) {
      const item = segments.get(id == null ? null : String(id));
      activeId = item ? String(id) : null;
      segments.forEach((entry, key) => entry.node.classList.toggle('active', key === activeId));
      if (!item) {
        if (cursor) cursor.setAttribute('visibility', 'hidden');
        if (dot) dot.setAttribute('visibility', 'hidden');
        if (detail) detail.textContent = 'Наведите на участок профиля или карты. Tab — выбрать участок, Enter — показать на карте.';
        if (emit) options.onSegmentHover?.(null);
        return;
      }
      const s = item.segment;
      station = clamp(finite(at) ? at : (s.from + s.to) / 2, s.from, s.to);
      const fraction = (station - s.from) / (s.to - s.from);
      const depth = s.depth_start + (s.depth_end - s.depth_start) * fraction;
      const slope = (s.depth_end - s.depth_start) / (s.to - s.from) * 100;
      const direction = Math.abs(slope) < 0.005 ? 'горизонтально' : slope > 0 ? 'заглубление' : 'подъём';
      const overlapping = (data.obstacles || []).filter(o => o.from <= station && o.to >= station);
      detail.textContent = `${fmt(station)} м от врезки · глубина верха ${fmt(depth)} м · `
        + `ДУ ${s.diameter == null ? '—' : s.diameter} · уклон ≈ ${fmt(Math.abs(slope))}% (${direction})`
        + (finite(s.height) ? ` · высота габарита ${fmt(s.height)} м` : '')
        + (overlapping.length ? ` · ${overlapping.map(obstacleLabel).join(', ')}` : '');
      cursor.setAttribute('x1', xScale(station)); cursor.setAttribute('x2', xScale(station));
      cursor.setAttribute('visibility', 'visible');
      dot.setAttribute('cx', xScale(station)); dot.setAttribute('cy', yScale(depth));
      dot.setAttribute('visibility', 'visible');
      if (emit) options.onSegmentHover?.(s.id);
    }
    function position(s, at) {
      const coordinate = coordinateAt(s.coordinates, (at - s.from) / (s.to - s.from));
      options.onPosition?.({segmentId: s.id, station: at, coordinate});
    }
    function eventStation(event, s) {
      if (!finite(event.clientX) || !chart) return (s.from + s.to) / 2;
      const rect = chart.getBoundingClientRect();
      if (!rect.width) return (s.from + s.to) / 2;
      let vx = (event.clientX - rect.left) / rect.width * VIEW.width;
      const matrix = chart.getScreenCTM?.();
      if (matrix && chart.createSVGPoint) {
        const point = chart.createSVGPoint(); point.x = event.clientX; point.y = finite(event.clientY) ? event.clientY : 0;
        vx = point.matrixTransform(matrix.inverse()).x;
      }
      return clamp((vx - VIEW.left) / (VIEW.right - VIEW.left) * data.total_length, s.from, s.to);
    }

    function setData(profile) {
      if (!profile || !Array.isArray(profile.segments) || !profile.segments.length) {
        setMessage('Для выбранного ОКС нет участков продольного профиля.'); return;
      }
      const valid = finite(profile.total_length) && profile.total_length > 0 && profile.segments.every(s =>
        finite(s.from) && finite(s.to) && s.to > s.from && s.from >= 0 && s.to <= profile.total_length + 0.01
        && finite(s.depth_start) && finite(s.depth_end) && s.depth_start >= 0 && s.depth_end >= 0);
      if (!valid) { setMessage('Данные профиля неполны: недостаточно сведений о длине или глубине участков.'); return; }
      reset(); data = profile;
      const rawLabel = String(profile.oks?.label || profile.oks?.key || '').trim();
      const label = /^ОКС(?:\s|$)/i.test(rawLabel) ? rawLabel : `ОКС ${rawLabel}`.trim();
      const rawSource = String(profile.source?.label || '').trim();
      const sourceLabel = /^Врезка(?:\s|$)/i.test(rawSource) ? rawSource : `Врезка ${rawSource}`.trim();
      const variant = options.variantLabel?.(profile.variant_id) || 'ID варианта: ' + (profile.variant_id ?? '—');
      shell(`Продольный профиль · ${label}`,
        `${sourceLabel} → ${label} · ${fmt(profile.total_length)} м · ${variant}`);
      const topDepths = profile.segments.flatMap(s => [s.depth_start, s.depth_end]);
      const maxSlope = Math.max(...profile.segments.map(s => Math.abs((s.depth_end - s.depth_start) / (s.to - s.from)))) * 100;
      container.appendChild(html('p', 'hp-stats',
        `Глубина верха: ${fmt(Math.min(...topDepths))}–${fmt(Math.max(...topDepths))} м · Макс. уклон ≈ ${fmt(maxSlope)}% (по округлённой выдаче)`));
      const conventions = html('p', 'hp-conventions',
        'Глубина вниз от условной горизонтальной поверхности до верха габарита трубы. Рельеф не учитывается.');
      container.appendChild(conventions);
      const chartWrap = html('div', 'hp-chart-wrap');
      chart = svg('svg', {viewBox: `0 0 ${VIEW.width} ${VIEW.height}`, class: 'hp-chart',
        role: 'group', 'aria-label': `Продольный профиль ${label}. Расстояние от врезки, глубина в метрах.`});
      chartWrap.appendChild(chart); container.appendChild(chartWrap);
      const obstacles = (profile.obstacles || []).filter(o => finite(o.from) && finite(o.to) && o.to >= o.from);
      const depthValues = profile.segments.flatMap(s => [s.depth_start + (finite(s.height) ? s.height : 0),
        s.depth_end + (finite(s.height) ? s.height : 0)]);
      obstacles.forEach(o => [o.top_depth, o.bottom_depth, o.min_top_depth].forEach(v => { if (finite(v)) depthValues.push(v); }));
      const rawMax = Math.max(1, ...depthValues) * 1.15;
      const step = rawMax <= 5 ? 1 : rawMax <= 10 ? 2 : Math.ceil(rawMax / 5);
      const maxDepth = Math.ceil(rawMax / step) * step;
      xScale = n => VIEW.left + n / profile.total_length * (VIEW.right - VIEW.left);
      yScale = n => VIEW.top + n / maxDepth * (VIEW.bottom - VIEW.top);
      chart.appendChild(svg('text', {x: 8, y: 16, class: 'hp-axis-label'}, 'Глубина, м'));
      chart.appendChild(svg('text', {x: VIEW.right, y: 248, 'text-anchor': 'end', class: 'hp-axis-label'}, 'Расстояние от врезки, м'));
      for (let d = 0; d <= maxDepth; d += step) {
        chart.appendChild(svg('line', {x1: VIEW.left, x2: VIEW.right, y1: yScale(d), y2: yScale(d),
          class: d === 0 ? 'hp-ground' : 'hp-grid'}));
        chart.appendChild(svg('text', {x: VIEW.left - 10, y: yScale(d) + 4, 'text-anchor': 'end', class: 'hp-axis-label'}, fmt(d, 0)));
      }
      for (let i = 0; i <= 5; i++) {
        const at = profile.total_length * i / 5;
        chart.appendChild(svg('line', {x1: xScale(at), x2: xScale(at), y1: VIEW.top, y2: VIEW.bottom, class: 'hp-grid'}));
        chart.appendChild(svg('text', {x: xScale(at), y: VIEW.bottom + 17, 'text-anchor': i === 5 ? 'end' : i === 0 ? 'start' : 'middle',
          class: 'hp-axis-label'}, fmt(at, profile.total_length < 10 ? 1 : 0)));
      }
      chart.appendChild(svg('text', {x: VIEW.left + 5, y: VIEW.top - 7, class: 'hp-surface-label'}, 'Условная поверхность'));
      const obstacleButtons = html('div', 'hp-obstacles');
      obstacleButtons.setAttribute('aria-label', 'Пересечения с препятствиями');
      obstacles.forEach(o => {
        const left = xScale(clamp(o.from, 0, profile.total_length));
        const right = xScale(clamp(o.to, 0, profile.total_length));
        const group = svg('g', {class: 'hp-obstacle', 'data-obstacle-id': o.id, tabindex: '0',
          role: 'button', 'aria-label': obstacleText(o)});
        group.appendChild(svg('title', {}, obstacleText(o)));
        group.appendChild(svg('rect', {x: left - 2, y: 18, width: Math.max(4, right - left + 4), height: 10,
          rx: 2, class: 'hp-obstacle-footprint'}));
        if (finite(o.top_depth) && finite(o.bottom_depth) && o.bottom_depth >= o.top_depth) {
          group.appendChild(svg('rect', {x: left, y: yScale(o.top_depth), width: Math.max(2, right - left),
            height: Math.max(2, yScale(o.bottom_depth) - yScale(o.top_depth)), class: 'hp-obstacle-band', 'data-obstacle-band': o.id}));
        } else if (finite(o.top_depth)) {
          group.appendChild(svg('line', {x1: left, x2: right, y1: yScale(o.top_depth), y2: yScale(o.top_depth), class: 'hp-obstacle-level'}));
        }
        if (finite(o.min_top_depth)) {
          group.appendChild(svg('line', {x1: left, x2: Math.max(left + 2, right), y1: yScale(o.min_top_depth),
            y2: yScale(o.min_top_depth), class: 'hp-obstacle-threshold', 'data-obstacle-threshold': o.id}));
        }
        const show = () => {
          const at = (o.from + o.to) / 2;
          const s = profile.segments.find(segment => segment.from <= at && segment.to >= at);
          setActive(s?.id, at, true);
          if (s) position(s, at);
          detail.textContent = obstacleText(o);
        };
        group.addEventListener('mouseenter', show); group.addEventListener('focus', show);
        group.addEventListener('click', show);
        group.addEventListener('keydown', event => {
          if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); show(); }
        });
        chart.appendChild(group);
        const button = html('button', 'hp-obstacle-button', `${obstacleLabel(o)} · ${fmt(o.from, 1)}–${fmt(o.to, 1)} м`);
        button.setAttribute('type', 'button'); button.setAttribute('data-obstacle-button', o.id);
        button.setAttribute('title', obstacleText(o));
        button.addEventListener('click', show); button.addEventListener('focus', show);
        obstacleButtons.appendChild(button);
      });
      profile.segments.forEach(s => {
        const group = svg('g', {class: 'hp-segment', tabindex: '0', role: 'button', 'data-segment-id': s.id,
          'aria-label': `Участок ${s.id}, ${fmt(s.from)}–${fmt(s.to)} м, глубина ${fmt(s.depth_start)}–${fmt(s.depth_end)} м, ДУ ${s.diameter}`});
        const a = [xScale(s.from), yScale(s.depth_start)], b = [xScale(s.to), yScale(s.depth_end)];
        if (finite(s.height) && s.height > 0) {
          group.appendChild(svg('polygon', {class: 'hp-pipe-band', points: [a, b,
            [b[0], yScale(s.depth_end + s.height)], [a[0], yScale(s.depth_start + s.height)]].map(c => c.join(',')).join(' ')}));
        }
        group.appendChild(svg('line', {x1: a[0], y1: a[1], x2: b[0], y2: b[1], class: 'hp-pipe-top'}));
        group.appendChild(svg('line', {x1: a[0], y1: a[1], x2: b[0], y2: b[1], class: 'hp-hit'}));
        const hover = event => { const at = eventStation(event, s); setActive(s.id, at, true); position(s, at); };
        group.addEventListener('mousemove', hover); group.addEventListener('mouseenter', hover);
        group.addEventListener('click', hover);
        group.addEventListener('mouseleave', () => setActive(null, null, true));
        group.addEventListener('focus', () => setActive(s.id, (s.from + s.to) / 2, true));
        group.addEventListener('blur', () => setActive(null, null, true));
        group.addEventListener('keydown', event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault(); const at = activeId === String(s.id) && finite(station) ? station : (s.from + s.to) / 2;
            setActive(s.id, at, true); position(s, at);
          } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
            event.preventDefault(); const at = clamp((finite(station) ? station : (s.from + s.to) / 2)
              + (event.key === 'ArrowRight' ? 1 : -1) * (s.to - s.from) / 20, s.from, s.to);
            setActive(s.id, at, true); position(s, at);
          }
        });
        segments.set(String(s.id), {node: group, segment: s}); chart.appendChild(group);
      });
      cursor = svg('line', {class: 'hp-cursor', y1: VIEW.top, y2: VIEW.bottom, visibility: 'hidden'});
      dot = svg('circle', {class: 'hp-cursor-dot', r: 4, visibility: 'hidden'});
      chart.appendChild(cursor); chart.appendChild(dot);
      detail = html('div', 'hp-detail'); detail.setAttribute('aria-live', 'polite'); container.appendChild(detail);
      setActive(null, null, false);
      const legend = html('p', 'hp-legend', '━━ верх трубы / полоса — расчётный габарит · ▰ препятствие · ┄ минимальная глубина по правилу');
      container.appendChild(legend);
      if (obstacles.length) container.appendChild(obstacleButtons);
      if (!obstacles.length) container.appendChild(html('p', 'hp-note', 'Физических пересечений с препятствиями на этом пути нет.'));
      const help = html('details', 'hp-help');
      help.appendChild(html('summary', '', 'Как читать профиль'));
      const notes = Array.isArray(profile.notes) && profile.notes.length ? profile.notes : [
        'Уклоны приблизительные: восстановлены из округлённых глубин выдачи.',
        'Показаны физические пересечения; нормативные отступы не изображены как размеры препятствий.'
      ];
      ['Горизонтальный и вертикальный масштабы различаются.', ...notes].forEach(text => help.appendChild(html('p', 'hp-note', String(text))));
      container.appendChild(help);
    }
    clear();
    return {setData, setMessage, clear, highlightSegment: id => setActive(id, null, false)};
  }
  global.HeatnetProfile = {create};
})(typeof window !== 'undefined' ? window : globalThis);
