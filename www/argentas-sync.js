/*
 * ARGENTAS — sincronización automática entre dos celulares.
 *
 * La interfaz original NO se rediseña.
 * Este módulo solamente:
 *   - descubre otro Argentas en la misma red/hotspot mediante el plugin Android;
 *   - sincroniza los datos persistidos;
 *   - muestra un único indicador pequeño de conexión encima del indicador
 *     ORIGINAL de estado de caja.
 */

const DATA_KEYS = [
  'argentas_products',
  'argentas_products_v6',
  'argentas_products_v5',
  'argentas_sales',
  'argentas_cash',
  'argentas_expenses',
  'argentas_closures',
  'argentas_modo_playa'
];

const Native = window.Capacitor?.Plugins?.ArgentasSync;
const state = {
  connected: false,
  applying: false,
  booted: false
};

function snapshot() {
  const data = {};
  for (const key of DATA_KEYS) {
    const value = localStorage.getItem(key);
    if (value !== null) data[key] = value;
  }
  return data;
}

function mergeSnapshot(data) {
  for (const key of DATA_KEYS) {
    if (!(key in data)) continue;

    const incoming = data[key];
    const current = localStorage.getItem(key);

    if (current === null) {
      localStorage.setItem(key, incoming);
      continue;
    }

    if (
      key === 'argentas_sales' ||
      key === 'argentas_expenses' ||
      key === 'argentas_closures'
    ) {
      try {
        const a = JSON.parse(current) || [];
        const b = JSON.parse(incoming) || [];
        const map = new Map(
          a.map(item => [item.id || JSON.stringify(item), item])
        );

        b.forEach(item => {
          map.set(item.id || JSON.stringify(item), item);
        });

        localStorage.setItem(
          key,
          JSON.stringify([...map.values()])
        );
      } catch {
        localStorage.setItem(key, incoming);
      }
    } else if (current !== incoming) {
      localStorage.setItem(key, incoming);
    }
  }
}

async function send(message) {
  if (!Native) return;

  try {
    await Native.send({
      data: JSON.stringify(message)
    });
  } catch (_) {}
}

function setConnected(connected) {
  state.connected = !!connected;

  const indicators = [
    ...document.querySelectorAll('.argentas-network-status')
  ];

  if (!indicators.length) return;

  const color = state.connected
    ? '#22c55e'
    : '#ef4444';

  indicators.forEach(indicator => {
    const dot = indicator.querySelector(
      '[data-argentas-dot]'
    );

    const label = indicator.querySelector(
      '[data-argentas-label]'
    );

    if (dot) {
      dot.style.background = color;
    }

    if (label) {
      label.textContent = state.connected
        ? 'CONECTADO'
        : 'DESCONECTADO';

      label.style.color = color;
    }
  });
}

function findOriginalHeaderCajas() {
  const header = document.querySelector('header');

  if (!header) return [];

  const result = [];

  // Indicador original de Caja en escritorio.
  const desktop = [...header.querySelectorAll('*')].find(el => {
    const text = (el.textContent || '').trim();
    const cls =
      typeof el.className === 'string'
        ? el.className
        : '';

    return (
      el.children.length === 0 &&
      (
        text === 'CAJA ABIERTA' ||
        text === 'CAJA CERRADA'
      ) &&
      cls.includes('rounded-full') &&
      cls.includes('tracking-widest') &&
      cls.includes('px-3')
    );
  });

  if (desktop) {
    result.push(desktop);
  }

  // Indicador original de Caja en móvil.
  const mobile = [...header.querySelectorAll('*')].find(el => {
    const text = (el.textContent || '').trim();
    const cls =
      typeof el.className === 'string'
        ? el.className
        : '';

    return (
      el.children.length === 0 &&
      (
        text === 'ABIERTA' ||
        text === 'CERRADA'
      ) &&
      cls.includes('rounded-full') &&
      cls.includes('tracking-widest') &&
      cls.includes('px-2.5')
    );
  });

  if (mobile) {
    result.push(mobile);
  }

  return result;
}

function removeOldInjectedIndicator() {
  document
    .querySelectorAll(
      '.argentas-network-status, .argentas-network-status-wrap'
    )
    .forEach(el => el.remove());
}

function makeIndicator() {
  const indicator = document.createElement('div');

  indicator.className =
    'argentas-network-status';

  Object.assign(indicator.style, {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: '4px',
    minHeight: '11px',
    fontSize: '8px',
    lineHeight: '1',
    fontWeight: '900',
    letterSpacing: '.12em',
    whiteSpace: 'nowrap',
    userSelect: 'none',
    pointerEvents: 'none'
  });

  const dot = document.createElement('span');

  dot.setAttribute(
    'data-argentas-dot',
    '1'
  );

  Object.assign(dot.style, {
    width: '6px',
    height: '6px',
    borderRadius: '50%',
    display: 'inline-block',
    flexShrink: '0'
  });

  const label = document.createElement('span');

  label.setAttribute(
    'data-argentas-label',
    '1'
  );

  label.textContent = state.connected
    ? 'CONECTADO'
    : 'DESCONECTADO';

  indicator.append(dot, label);

  return indicator;
}

function installIndicator() {
  const existing = [
    ...document.querySelectorAll(
      '.argentas-network-status'
    )
  ];

  const cajas = findOriginalHeaderCajas();

  if (!cajas.length) {
    return existing.length > 0;
  }

  for (const caja of cajas) {
    if (!caja.parentElement) continue;

    if (
      caja.parentElement.classList?.contains(
        'argentas-network-status-wrap'
      )
    ) {
      continue;
    }

    const parent = caja.parentElement;

    const wrapper =
      document.createElement('div');

    wrapper.className =
      'argentas-network-status-wrap';

    Object.assign(wrapper.style, {
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'flex-end',
      justifyContent: 'center',
      gap: '2px',
      flexShrink: '0'
    });

    const indicator = makeIndicator();

    parent.insertBefore(wrapper, caja);

    wrapper.append(
      indicator,
      caja
    );
  }

  setConnected(state.connected);

  return true;
}

function reloadAfterRemote() {
  setTimeout(() => {
    location.reload();
  }, 120);
}

async function boot() {
  if (state.booted) return;

  state.booted = true;

  removeOldInjectedIndicator();

  installIndicator();

  if (!Native) {
    setConnected(false);
    return;
  }

  try {
    await Native.addListener(
      'status',
      ({ status }) => {
        const connected =
          status === 'Conectado';

        setConnected(connected);

        if (connected) {
          send({
            type: 'hello',
            data: snapshot()
          });
        }
      }
    );

    await Native.addListener(
      'message',
      ({ data }) => {
        try {
          const message =
            JSON.parse(data);

          if (message.type === 'hello') {
            state.applying = true;

            mergeSnapshot(
              message.data || {}
            );

            state.applying = false;

            send({
              type: 'hello',
              data: snapshot()
            });

            reloadAfterRemote();

          } else if (
            message.type === 'storage'
          ) {
            state.applying = true;

            mergeSnapshot(
              message.data || {}
            );

            state.applying = false;

            reloadAfterRemote();
          }

        } catch (_) {}
      }
    );

    await Native.start();

  } catch (_) {
    setConnected(false);
  }
}

const originalSetItem =
  localStorage.setItem.bind(
    localStorage
  );

localStorage.setItem = (
  key,
  value
) => {
  originalSetItem(
    key,
    String(value)
  );

  if (
    DATA_KEYS.includes(key) &&
    !state.applying &&
    state.connected
  ) {
    send({
      type: 'storage',
      data: {
        [key]: String(value)
      }
    });
  }
};

// React renderiza el encabezado de forma
// asincrónica. Volvemos a comprobar la
// inserción después de cada render.
// Los indicadores originales no se modifican.
const observer =
  new MutationObserver(() => {
    installIndicator();
  });

function startObservers() {
  if (!document.documentElement) return;

  observer.observe(
    document.documentElement,
    {
      childList: true,
      subtree: true
    }
  );

  setTimeout(
    boot,
    350
  );
}

if (
  document.readyState === 'loading'
) {
  document.addEventListener(
    'DOMContentLoaded',
    startObservers,
    { once: true }
  );
} else {
  startObservers();
}

window.addEventListener(
  'load',
  () => {
    installIndicator();

    setTimeout(
      boot,
      350
    );
  },
  { once: true }
);
