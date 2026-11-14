import React, { useEffect, useRef, useState } from "react";
import { createId } from "./id";
import { api, login, refreshCsrf } from "./api";
import "./style.css";
type Row = Record<string, any>;
const dimensions: Record<string, string> = {
  acidity: "Кислотность",
  bitterness: "Горечь",
  sweetness: "Сладость",
  astringency: "Терпкость / сухость",
  body: "Плотность",
  aromaIntensity: "Интенсивность аромата",
};
const day = () =>
  new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Yekaterinburg" }).format(
    new Date(),
  );
const format = (v: string) =>
  new Date(v).toLocaleString("ru-RU", {
    timeZone: "Asia/Yekaterinburg",
    day: "numeric",
    month: "long",
    hour: "2-digit",
    minute: "2-digit",
  });
export function App() {
  const quickLock = useRef(false);
  const [quick, setQuick] = useState<{ request: Row; event?: Row; failed?: boolean } | null>(null);
  const [ready, setReady] = useState(false),
    [user, setUser] = useState<Row | null>(null),
    [page, setPage] = useState("Сейчас"),
    [bags, setBags] = useState<Row[]>([]),
    [history, setHistory] = useState<Row[]>([]),
    [kind, setKind] = useState("consumptions"),
    [profile, setProfile] = useState<Row>({ default_cup_volume_ml: 250 }),
    [stats, setStats] = useState<Row>({ cups: 0, volume: 0 }),
    [modal, setModal] = useState<Row | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [query, setQuery] = useState(""),
    [start, setStart] = useState(day()),
    [end, setEnd] = useState(day()),
    [deleted, setDeleted] = useState(false),
    [offset, setOffset] = useState(0);
  const refreshId = useRef(0);
  async function refresh() {
    const id = ++refreshId.current;
    const cupHistory = page === "История" && kind === "consumptions";
    const validPeriod = !!start && !!end && start <= end;
    const today = day();
    const period = page === "Сейчас" ? `start=${today}&end=${today}` : `start=${start}&end=${end}`;
    const [b, p, s, rows] = await Promise.all([
      api("/bags?limit=100&q=" + encodeURIComponent(query)),
      api("/profile"),
      page === "Сейчас" || (cupHistory && validPeriod && !deleted)
        ? api(`/consumption-stats?${period}`)
        : null,
      page === "История" && (!cupHistory || validPeriod)
        ? api(`/${kind}?deleted=${deleted}&offset=${offset}${cupHistory ? "&" + period : ""}`)
        : [],
    ]);
    if (id !== refreshId.current) return;
    setBags(b);
    setProfile(p);
    if (s) setStats(s);
    setHistory(rows);
  }
  useEffect(() => {
    api("/auth/me")
      .then(setUser)
      .catch(() => {})
      .finally(() => setReady(true));
  }, []);
  useEffect(() => {
    if (user) refresh().catch((e) => setError(e.message));
  }, [user, page, kind, query, start, end, deleted, offset]);
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (modal) {
        e.preventDefault();
      }
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [modal]);
  async function run(fn: () => Promise<unknown>, message = "Сохранено", reload = true) {
    setBusy(true);
    setError("");
    try {
      await fn();
      if (reload) await refresh();
      setNotice(message);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  function open(type: string, extra: Row = {}) {
    setError("");
    setModal({ type, key: createId(), ...extra });
  }
  async function cup(bag?: Row) {
    open("cup", { bagId: bag?.id, volumeMl: profile.default_cup_volume_ml });
  }
  async function quickCup(request: Row) {
    if (quickLock.current) return;
    quickLock.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    setQuick({ request });
    try {
      const event = await api("/consumptions", "POST", request);
      setQuick({ request, event });
      try {
        await refresh();
      } catch {
        setError("Чашка сохранена, но счётчики не обновились. Обновите страницу.");
      }
    } catch {
      setQuick({ request, failed: true });
      setError("Не удалось подтвердить сохранение чашки. Повторите запрос — уже сохранённая чашка не добавится дважды.");
    } finally {
      quickLock.current = false;
      setBusy(false);
    }
  }
  async function undoQuick() {
    const event = quick?.event;
    if (!event) return;
    await run(async () => {
      const current = await api(`/consumptions/${event.id}`);
      if (!current.deleted_at)
        await api(`/consumptions/${event.id}?version=${current.version}`, "DELETE");
      setQuick(null);
    }, "Чашка отменена");
  }
  async function save(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget),
      v = Object.fromEntries(f.entries()),
      m = modal!;
    await run(async () => {
      if (m.type === "bag")
        await api(
          "/bags",
          "POST",
          {
            ...v,
            massG: Number(v.massG),
            ...(m.lotId ? { lotId: m.lotId } : {}),
          },
          m.key,
        );
      if (m.type === "cup")
        await api("/consumptions", "POST", {
          bagId: m.bagId || null,
          clientEventId: m.key,
          volumeMl: Number(v.volumeMl),
          coffeeLabel: v.coffeeLabel || null,
        });
      if (m.type === "tasting") {
        const sensory: Row = {};
        for (const d of Object.keys(dimensions)) {
          const value = v[d];
          sensory[d] =
            value && value !== "UNKNOWN"
              ? { status: "RATED", value: Number(value) }
              : {
                  status: value === "UNKNOWN" ? "UNKNOWN" : "UNANSWERED",
                  value: null,
                };
        }
        await api(
          "/tastings",
          "POST",
          {
            bagId: m.bagId,
            overallRating: Number(v.overallRating),
            repurchase: v.repurchase || null,
            comment: v.comment,
            sensory,
            consumptionEventId: m.eventId || null,
            recordConsumption: v.recordConsumption === "on",
          },
          m.key,
        );
      }
      setModal(null);
      if (m.eventId && m.eventId === quick?.event?.id) setQuick(null);
    });
  }
  const title =
    page === "Сейчас"
      ? "Хороший день начинается с кофе."
      : page === "История"
        ? "Каждая чашка — своя история."
        : page === "Настройки"
          ? "Ваш привычный ритуал."
          : "Следующая любимая пачка.";
  if (!ready) return <div className="loading">Открываем дневник…</div>;
  if (!user)
    return (
      <main className="login">
        <div className="brand">
          ◒ <b>мой кофе</b>
        </div>
        <p className="eyebrow">ЛИЧНЫЙ КОФЕЙНЫЙ ДНЕВНИК</p>
        <h1>
          Место для вашего
          <br />
          кофейного опыта.
        </h1>
        <p>Покупки, впечатления и маленькие ежедневные ритуалы.</p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            setBusy(true);
            try {
              await login(String(f.get("username")), String(f.get("password")));
              setUser(await api("/auth/me"));
              setError("");
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label>
            Логин
            <input
              name="username"
              autoComplete="username"
              required
              defaultValue="owner"
            />
          </label>
          <label>
            Пароль
            <input
              name="password"
              type="password"
              autoComplete="current-password"
              required
            />
          </label>
          <button disabled={busy} className="primary">
            {busy ? "Входим…" : "Открыть дневник →"}
          </button>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
        </form>
        <small>Личный доступ · Ваши данные на вашем сервере</small>
      </main>
    );
  return (
    <div className="layout">
      <aside>
        <div className="brand">
          ◒ <b>мой кофе</b>
        </div>
        <span className="subtitle">Дневник маленьких открытий</span>
        <nav>
          {["Сейчас", "История", "Что попробовать", "Настройки"].map((n, i) => (
            <button
              key={n}
              className={page === n ? "selected" : ""}
              onClick={() => {
                setPage(n);
                setOffset(0);
              }}
            >
              <span>{["◉", "▤", "✧", "⚙"][i]}</span>
              {n}
            </button>
          ))}
        </nav>
        <div className="aside-bottom">
          <span>
            В своём темпе.
            <br />
            По своему вкусу.
          </span>
          <small>Личный дневник / V1 в разработке</small>
        </div>
      </aside>
      <main>
        <header>
          <span>
            {new Date().toLocaleDateString("ru-RU", {
              timeZone: "Asia/Yekaterinburg",
              day: "numeric",
              month: "long",
              year: "numeric",
            })}
          </span>
          <button
            className="text"
            onClick={() =>
              run(async () => {
                await api("/auth/logout", "POST");
                setUser(null);
                setQuick(null);
                await refreshCsrf();
              }, "", false)
            }
          >
            Выйти ↗
          </button>
        </header>
        <section className="heading">
          <p className="eyebrow">
            {page === "Сейчас" ? "ВАШ ЕЖЕДНЕВНЫЙ РИТУАЛ" : page.toUpperCase()}
          </p>
          <h1>{title}</h1>
          <p>Замечайте, что нравится. Сохраняйте то, что хочется повторить.</p>
        </section>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        {quick && (
          <div role="status" className="notice">
            {quick.event ? `Учтена чашка · ${quick.event.volume_ml} мл` : quick.failed ? "Результат записи чашки не подтверждён" : "Сохраняем чашку…"}
            {quick.failed && <button disabled={busy} onClick={() => quickCup(quick.request)}>Повторить запись чашки</button>}
            {quick.event && <>
              <button disabled={busy} onClick={undoQuick}>Отменить чашку</button>
              <button disabled={busy} onClick={() => open("tasting", { bagId: quick.event!.bag_id, eventId: quick.event!.id })}>Оценить чашку</button>
              <button disabled={busy} aria-label="Закрыть сообщение о чашке" onClick={() => setQuick(null)}>×</button>
            </>}
          </div>
        )}
        {notice && (
          <div role="status" className="notice">
            {notice}
            <button
              onClick={() => setNotice("")}
              aria-label="Закрыть уведомление"
            >
              ×
            </button>
          </div>
        )}
        {page === "Сейчас" && (
          <>
            <section className="daily">
              <div>
                <p className="eyebrow">СЕГОДНЯ В ЧАШКЕ</p>
                <div className="numbers">
                  {stats.cups}
                  <span>чашек</span>
                  <i /> {stats.volume}
                  <span>мл</span>
                </div>
                <small>
                  Объём по умолчанию — {profile.default_cup_volume_ml} мл.
                  Настройте под свою чашку.
                </small>
              </div>
              <button className="light" disabled={busy} onClick={() => cup()}>
                ＋ Выпил другой кофе
              </button>
            </section>
            <div className="section-title">
              <h2>
                Пью сейчас{" "}
                <span>{bags.filter((b) => b.status === "ACTIVE").length}</span>
              </h2>
              <button className="primary" onClick={() => open("bag")}>
                ＋ Добавить кофе
              </button>
            </div>
            <div className="cards">
              {bags
                .filter((b) => b.status === "ACTIVE")
                .map((b) => (
                  <article key={b.id} className="card">
                    <div className="bag-art">
                      <span>
                        COFFEE
                        <br />
                        <b>{b.roaster_name || "ВАШ ЛОТ"}</b>
                        <em>{b.mass_g} г</em>
                      </span>
                    </div>
                    <div className="card-body">
                      <p className="eyebrow">
                        {b.roaster_name || "ЛИЧНЫЙ ЛОТ"}
                      </p>
                      <h2>{b.name}</h2>
                      <p className="muted">
                        {b.cup_count} чашек · {b.tasting_count} оценок{" "}
                        <b>
                          {b.rating
                            ? " · ★ " + Number(b.rating).toFixed(1)
                            : ""}
                        </b>
                      </p>
                      <button
                        disabled={busy || !!(quick && !quick.event)}
                        className="primary wide"
                        onClick={() => quickCup({ bagId: b.id, volumeMl: profile.default_cup_volume_ml, clientEventId: createId() })}
                      >
                        Выпил чашку · {profile.default_cup_volume_ml} мл
                      </button>
                      <div className="card-actions">
                        <button
                          onClick={() =>
                            open("tasting", { bagId: b.id, name: b.name })
                          }
                        >
                          Оценить
                        </button>
                        <button
                          disabled={busy}
                          onClick={() =>
                            run(
                              () =>
                                api(`/bags/${b.id}/status`, "POST", {
                                  status: "FINISHED",
                                  version: b.version,
                                }),
                              "Пачка завершена. История сохранена.",
                            )
                          }
                        >
                          Закончить ↗
                        </button>
                      </div>
                    </div>
                  </article>
                ))}
            </div>
            {!bags.some((b) => b.status === "ACTIVE") && (
              <div className="empty">
                <span>◒</span>
                <h2>Что сегодня в вашей чашке?</h2>
                <p>
                  Добавьте первую пачку — достаточно названия.
                  <br />
                  Или просто отметьте выпитый кофе.
                </p>
                <button onClick={() => open("bag")} className="primary">
                  Добавить первую пачку
                </button>
              </div>
            )}
            <details className="stock">
              <summary>
                В запасе · {bags.filter((b) => b.status === "STOCK").length}
              </summary>
              {bags
                .filter((b) => b.status === "STOCK")
                .map((b) => (
                  <div className="history-row" key={b.id}>
                    <b>{b.name}</b>
                    <button
                      onClick={() =>
                        run(() =>
                          api(`/bags/${b.id}/status`, "POST", {
                            status: "ACTIVE",
                            version: b.version,
                          }),
                        )
                      }
                    >
                      Открыть пачку
                    </button>
                  </div>
                ))}
            </details>
            <p className="footer-note">
              Хороший кофе — тот, который нравится вам.
            </p>
          </>
        )}
        {page === "История" && (
          <>
            {kind === "consumptions" && <div className="filters">
              <label>
                С
                <input
                  type="date"
                  value={start}
                  onChange={(e) => { setStart(e.target.value); setOffset(0); }}
                />
              </label>
              <label>
                По
                <input
                  type="date"
                  value={end}
                  onChange={(e) => { setEnd(e.target.value); setOffset(0); }}
                />
              </label>
              <button onClick={() => { const today = day(); setStart(today); setEnd(today); setOffset(0); }}>Сегодня</button>
              {(!start || !end || start > end) ? <span>Укажите корректный период</span> : !deleted && <b>
                {stats.cups} чашек · {stats.volume} мл за период
              </b>}
            </div>}
            <div className="tabs">
              {[
                ["consumptions", "Выпито"],
                ["bags", "Пачки"],
                ["tastings", "Оценки"],
                ["lots", "Лоты"],
              ].map(([k, n]) => (
                <button
                  key={k}
                  className={kind === k ? "selected" : ""}
                  onClick={() => {
                    setKind(k);
                    setOffset(0);
                  }}
                >
                  {n}
                </button>
              ))}
            </div>
            <label className="check">
              <input
                type="checkbox"
                checked={deleted}
                onChange={(e) => { setDeleted(e.target.checked); setOffset(0); }}
              />
              Показать удалённые
            </label>
            {history.map((r) => (
              <article className="history-row" key={r.id}>
                <div>
                  <b>
                    {r.name ||
                      bags.find((b) => b.id === r.bag_id)?.name ||
                      (r.bag_id
                        ? "Удалённая или архивная пачка"
                        : r.coffee_label || "Неизвестный кофе")}
                  </b>
                  <p>
                    {r.consumed_at
                      ? `${format(r.consumed_at)} · ${r.volume_ml} мл`
                      : r.overall_rating
                        ? `★ ${r.overall_rating}/5 · ${format(r.tasted_at)}`
                        : r.status || "Личный лот"}
                  </p>
                  {r.comment && <p>{r.comment}</p>}
                </div>
                <div className="row-actions">
                  {kind === "consumptions" &&
                    r.bag_id &&
                    !r.tasting_id &&
                    !deleted && (
                      <button
                        onClick={() =>
                          open("tasting", { bagId: r.bag_id, eventId: r.id })
                        }
                      >
                        Оценить
                      </button>
                    )}
                  {kind === "bags" && !deleted && (
                    <>
                      <button
                        onClick={() =>
                          open("bag", { lotId: r.lot_id, name: r.name })
                        }
                      >
                        Купил ещё
                      </button>
                      <button
                        onClick={() =>
                          run(() =>
                            api(`/bags/${r.id}/status`, "POST", {
                              status: "ACTIVE",
                              version: r.version,
                            }),
                          )
                        }
                      >
                        Открыть
                      </button>
                    </>
                  )}
                  <button
                    disabled={busy}
                    onClick={() =>
                      run(() =>
                        deleted
                          ? api(`/${kind}/${r.id}/restore`, "POST", {
                              version: r.version,
                            })
                          : api(
                              `/${kind}/${r.id}?version=${r.version}`,
                              "DELETE",
                            ),
                      )
                    }
                  >
                    {deleted ? "Восстановить" : "Удалить"}
                  </button>
                </div>
              </article>
            ))}
            {!history.length && (
              <div className="empty">
                <h2>Здесь пока тихо</h2>
                <p>Ваши покупки и чашки появятся здесь.</p>
              </div>
            )}
            <div className="pagination">
              <button
                disabled={!offset}
                onClick={() => setOffset(Math.max(0, offset - 20))}
              >
                ← Назад
              </button>
              <button
                disabled={history.length < 20}
                onClick={() => setOffset(offset + 20)}
              >
                Далее →
              </button>
            </div>
          </>
        )}
        {page === "Что попробовать" && (
          <div className="empty">
            <span>✧</span>
            <h2>Новые открытия впереди</h2>
            <p>
              Каталог ещё не загружен.
              <br />
              Интеграции FOLK и Tasty Coffee находятся в разработке.
            </p>
            <p className="muted">
              Личные рекомендации появятся после импорта
              <br />и привязки оценённых покупок к каталогу.
            </p>
          </div>
        )}
        {page === "Настройки" && (
          <section className="settings">
            <h2>Оборудование и чашка</h2>
            <form
              key={profile.version}
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                run(() =>
                  api("/profile", "PATCH", {
                    name: f.get("name"),
                    defaultCupVolumeMl: Number(f.get("volume")),
                    version: profile.version,
                  }),
                );
              }}
            >
              <label>
                Название профиля
                <input
                  name="name"
                  defaultValue={profile.name}
                  required
                  maxLength={200}
                />
              </label>
              <label>
                Объём выпитой чашки, мл
                <input
                  name="volume"
                  type="number"
                  min={10}
                  max={2000}
                  defaultValue={profile.default_cup_volume_ml}
                  required
                />
              </label>
              <p>
                Это объём напитка, который вы выпиваете, а не воды для
                заваривания. Старые записи сохранят свой объём.
              </p>
              <button className="primary" disabled={busy}>
                Сохранить настройки
              </button>
            </form>
            <h3>Часовой пояс</h3>
            <p>Asia/Yekaterinburg · UTC+5</p>
            <h3>Ваш способ приготовления</h3>
            <p>
              Автоматический пуровер · бумажный фильтр
              <br />
              MHW-3BOMBER Race M1 · Kitfort КТ-7570
            </p>
          </section>
        )}
      </main>
      {modal && (
        <div className="overlay">
          <section
            className="dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="dialog-title"
          >
            <button
              className="close"
              aria-label="Закрыть форму"
              onClick={() => {
                if (confirm("Закрыть форму без сохранения?")) setModal(null);
              }}
            >
              ×
            </button>
            <p className="eyebrow">МОЙ КОФЕ</p>
            <h2 id="dialog-title">
              {modal.type === "bag"
                ? "Новая пачка"
                : modal.type === "cup"
                  ? "Ещё одна чашка"
                  : "Как вам кофе?"}
            </h2>
            {modal.name && <p>{modal.name}</p>}
            <form onSubmit={save}>
              {modal.type === "bag" && (
                <>
                  {!modal.lotId && (
                    <>
                      <label>
                        Название кофе
                        <input
                          name="name"
                          required
                          maxLength={200}
                          autoFocus
                          placeholder="Например, Эфиопия Гуджи"
                        />
                      </label>
                      <label>
                        Обжарщик <small>необязательно</small>
                        <input name="roasterName" maxLength={120} />
                      </label>
                    </>
                  )}
                  <div className="form-row">
                    <label>
                      Масса, г
                      <input
                        name="massG"
                        type="number"
                        min={1}
                        max={5000}
                        defaultValue={250}
                        required
                      />
                    </label>
                    <label>
                      Статус
                      <select name="status">
                        <option value="ACTIVE">Пью сейчас</option>
                        <option value="STOCK">В запасе</option>
                      </select>
                    </label>
                  </div>
                  <label>
                    Заметка
                    <textarea name="note" maxLength={5000} />
                  </label>
                </>
              )}
              {modal.type === "cup" && (
                <>
                  <p>
                    {modal.bagId
                      ? "Из выбранной пачки"
                      : "Неизвестный лот / другой кофе"}
                  </p>
                  <label>
                    Объём напитка, мл
                    <input
                      autoFocus
                      name="volumeMl"
                      type="number"
                      min={10}
                      max={2000}
                      defaultValue={modal.volumeMl}
                      required
                    />
                  </label>
                  {!modal.bagId && (
                    <label>
                      Что пили? <small>необязательно</small>
                      <input
                        name="coffeeLabel"
                        maxLength={200}
                        placeholder="Фильтр в кофейне"
                      />
                    </label>
                  )}
                </>
              )}
              {modal.type === "tasting" && (
                <>
                  <label>
                    Общее впечатление
                    <select name="overallRating" required defaultValue="">
                      <option value="" disabled>
                        Выберите оценку
                      </option>
                      {[
                        "Совсем не понравилось",
                        "Скорее не понравилось",
                        "Нормально",
                        "Понравилось",
                        "Очень понравилось",
                      ].map((s, i) => (
                        <option key={i} value={i + 1}>
                          {i + 1} — {s}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Купили бы ещё?
                    <select name="repurchase">
                      <option value="">Пока не знаю</option>
                      <option value="YES">Да</option>
                      <option value="MAYBE">Возможно</option>
                      <option value="NO">Нет</option>
                    </select>
                  </label>
                  <details>
                    <summary>Подробнее о вкусе · 6 шкал</summary>
                    <p className="muted">
                      Интенсивность ощущения, а не оценка качества: от 1 (не
                      ощущаю / слабая) до 5 (очень выраженная).
                    </p>
                    {Object.entries(dimensions).map(([k, n]) => (
                      <label key={k}>
                        {n}
                        <select name={k}>
                          <option value="">Пропустить</option>
                          <option value="UNKNOWN">Не могу оценить</option>
                          {[1, 2, 3, 4, 5].map((v) => (
                            <option key={v} value={v}>
                              {v} —{" "}
                              {
                                [
                                  "очень слабая",
                                  "слабая",
                                  "средняя",
                                  "сильная",
                                  "очень сильная",
                                ][v - 1]
                              }
                            </option>
                          ))}
                        </select>
                      </label>
                    ))}
                  </details>
                  <label>
                    Ваши впечатления
                    <textarea name="comment" maxLength={5000} />
                  </label>
                  {modal.eventId ? (
                    <p>✓ Чашка уже учтена</p>
                  ) : (
                    <label className="check">
                      <input name="recordConsumption" type="checkbox" />
                      Также учесть выпитую чашку
                    </label>
                  )}
                </>
              )}
              {error && (
                <p role="alert" className="error">
                  {error}
                </p>
              )}
              <button className="primary wide" disabled={busy}>
                {busy ? "Сохраняем…" : "Сохранить"}
              </button>
            </form>
          </section>
        </div>
      )}
    </div>
  );
}
