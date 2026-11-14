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
const sensoryNumber = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 2 });
const brewMethods = { AUTO_POUR_OVER: "Автоматический пуровер", POUR_OVER: "Пуровер", DRIP: "Капельный", IMMERSION: "Иммерсия", ESPRESSO: "Эспрессо", OTHER: "Другой" };
const additions = { NONE: "Без добавок", MILK: "Молоко", SUGAR: "Сахар", BOTH: "Молоко и сахар", UNKNOWN: "Неизвестно" };
const recipeNumbers = [
  { name: "grindClicks", label: "Общее число щелчков от нуля, включая полные обороты", min: 0, max: 2147483647, step: 1 },
  { name: "doseG", label: "Доза кофе, г", min: 0.1, max: 200, step: "any" },
  { name: "waterG", label: "Вода для заваривания, г", min: 1, max: 5000, step: "any" },
  { name: "temperatureC", label: "Температура, °C", min: 1, max: 100, step: "any" },
  { name: "durationSeconds", label: "Время приготовления, с", min: 1, max: 3600, step: 1 },
];
const recipeTexts = [
  { name: "grind", label: "Помол (текст, вместо щелчков)", maxLength: 200 },
  { name: "water", label: "Описание воды", maxLength: 200 },
  { name: "filterNote", label: "Фильтр или заметка о рецепте", maxLength: 500 },
];

const tasteWishes = {
  LESS_ACIDITY: "Меньше кислотности", MORE_ACIDITY: "Больше кислотности",
  LESS_BITTERNESS: "Меньше горечи", MORE_SWEETNESS: "Больше сладости",
  LESS_DRYNESS: "Меньше сухости", MORE_BODY: "Больше плотности",
  LESS_BODY: "Меньше плотности", MORE_AROMA: "Больше аромата",
  NOTHING_TO_CHANGE: "Ничего не менять",
};

function readRecipe(form: FormData) {
  return Object.fromEntries([
    ...["method", "milkSugar", ...recipeTexts.map(field => field.name)]
      .map(name => [name, form.get(name) || null]),
    ...recipeNumbers.map(({ name }) => {
      const value = form.get(name);
      return [name, value == null || value === "" ? null : Number(value)];
    }),
  ]);
}

function RecipeFields({ recipe, title = "Обычный рецепт" }: { recipe?: Row; title?: string }) {
  return (
    <>
      <h3>{title}</h3>
      {([{ name: "method", label: "Метод заваривания", options: brewMethods },
        { name: "milkSugar", label: "Молоко и сахар", options: additions }]).map(({name, label, options}) => (
        <label key={name}>{label}
          <select name={name} defaultValue={recipe?.[name] ?? ""}>
            <option value="">Не указано</option>
            {Object.entries(options).map(([value, text]) => <option key={value} value={value}>{text}</option>)}
          </select>
        </label>
      ))}
      {recipeNumbers.map(({ label, ...field }) => (
        <label key={field.name}>{label}
          <input {...field} type="number" defaultValue={recipe?.[field.name] ?? ""} />
        </label>
      ))}
      {recipeTexts.map(({ label, ...field }) => (
        <label key={field.name}>{label}
          <input {...field} defaultValue={recipe?.[field.name] ?? ""} />
        </label>
      ))}
    </>
  );
}

function BagPhotos({ bag, committed }: { bag: Row; committed: () => Promise<void> }) {
  const [ids, setIds] = useState<string[]>(bag.photos?.ids ?? []);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function update(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try { await action(); }
    catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  return (
    <section aria-label="Фото пачки">
      {ids.map((id, index) => (
        <div key={id} className="photo-item">
          <a href={`/api/v1/media/${id}`} target="_blank" rel="noreferrer">
            <img src={`/api/v1/media/${id}?thumbnail=true`} alt={`Фото пачки ${index + 1}`} />
          </a>
          {index === 0 ? <span>Обложка</span> : <button disabled={busy}
            onClick={() => setIds([id, ...ids.filter(value => value !== id)])}>На обложку</button>}
          {index > 0 && <button disabled={busy} onClick={() => {
            const next = [...ids];
            [next[index - 1], next[index]] = [next[index], next[index - 1]];
            setIds(next);
          }}>Выше</button>}
          <button disabled={busy} onClick={() => setIds(ids.filter(value => value !== id))}>Убрать фото</button>
        </div>
      ))}
      <label>Загрузить фото
        <input type="file" accept="image/jpeg,image/png,image/webp" disabled={busy || ids.length >= 5}
          onChange={e => {
            const file = e.target.files?.[0];
            e.target.value = "";
            if (!file) return;
            void update(async () => {
              const data = new FormData();
              data.set("file", file);
              const photo = await api("/media", "POST", data);
              setIds([...ids, photo.id]);
            });
          }} />
      </label>
      {error && <p role="alert">{error}</p>}
      <button className="primary" disabled={busy} onClick={() => update(async () => {
        await api(`/bags/${bag.id}/photos`, "PUT", { mediaIds: ids });
        await committed();
      })}>Сохранить фото</button>
    </section>
  );
}

function SensorySummary({ summary }: { summary?: Record<string, { average: number; count: number }> }) {
  if (!summary || !Object.keys(summary).length) return null;
  return (
    <details>
      <summary>Сводка вкуса</summary>
      <ul>
        {Object.entries(dimensions).map(([key, label]) => {
          const measurement = summary[key];
          return measurement && <li key={key}>{label}: {sensoryNumber.format(measurement.average)} из 5 ({measurement.count} изм.)</li>;
        })}
      </ul>
    </details>
  );
}
const format = (v: string) =>
  new Date(v).toLocaleString("ru-RU", {
    timeZone: "Asia/Yekaterinburg",
    day: "numeric",
    month: "long",
    hour: "2-digit",
    minute: "2-digit",
  });
const localCupTime = (value: string) =>
  new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Yekaterinburg",
    dateStyle: "short",
    timeStyle: "medium",
  }).format(new Date(value)).replace(" ", "T");
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
    [profiles, setProfiles] = useState<Row[]>([]),
    [quickProfile, setQuickProfile] = useState(""),
    [editingProfile, setEditingProfile] = useState<string | null>(null),
    [stats, setStats] = useState<Row>({ cups: 0, volume: 0 }),
    [modal, setModal] = useState<Row | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [query, setQuery] = useState(""),
    [historyStatus, setHistoryStatus] = useState(""),
    [historyRating, setHistoryRating] = useState(""),
    [coffee, setCoffee] = useState(""),
    [historySort, setHistorySort] = useState("recent"),
    [start, setStart] = useState(day()),
    [end, setEnd] = useState(day()),
    [deleted, setDeleted] = useState(false),
    [offset, setOffset] = useState(0);
  const refreshId = useRef(0);
  async function refresh() {
    const id = ++refreshId.current;
    const cupHistory = page === "История" && kind === "consumptions";
    const validPeriod = (!cupHistory && !start && !end) || (!!start && !!end && start <= end);
    const today = day();
    const period = page === "Сейчас" ? `start=${today}&end=${today}` : start && end ? `start=${start}&end=${end}` : "";
    const filters = new URLSearchParams({ q: query, sort: historySort });
    if (kind === "bags" && historyStatus) filters.set("status", historyStatus);
    if (kind !== "consumptions" && historyRating) filters.set("rating", historyRating);
    if (cupHistory && coffee) filters.set("coffee", coffee);
    const [b, p, s, rows, equipment] = await Promise.all([
      api("/bags?limit=100"),
      api("/profile"),
      page === "Сейчас" || (cupHistory && validPeriod && !deleted)
        ? api(`/consumption-stats?${period}${cupHistory ? "&" + filters : ""}`)
        : null,
      page === "История" && validPeriod
        ? api(`/${kind}?deleted=${deleted}&offset=${offset}&limit=21&${filters}&${period}`)
        : [],
      api("/brew-profiles"),
    ]);
    if (id !== refreshId.current) return;
    setBags(b);
    setProfile(p);
    setProfiles(equipment);
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
  }, [user, page, kind, query, start, end, deleted, offset, historyStatus, historyRating, coffee, historySort]);
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
    const key = createId();
    setModal({ type, key, ...extra });
    if (type === "tasting" && !extra.tasting && extra.bagId) {
      void api(`/bags/${extra.bagId}/last-grind`).then(grind => {
        setModal(current => current?.key === key && !current.recipeChanged ? { ...current, lastGrind: grind } : current);
      }).catch(() => {});
    }
  }
  async function cup(bag?: Row) {
    open("cup", { bagId: bag?.id, profileId: profile.id ?? "", volumeMl: profile.default_cup_volume_ml });
  }
  const selectedQuick = profiles.find(item => item.id === quickProfile) ?? profile;
  const settingsProfile = profiles.find(item => item.id === editingProfile) ?? profile;
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
      if (m.type === "linkTasting")
        await api(`/consumptions/${m.event.id}/tasting-link`, "PUT", {
          tastingId: v.tastingId, version: m.event.version,
        });
      if (m.type === "finalImpression")
        await api(`/bags/${m.record.id}/final-impression`, "PUT", {
          version: m.record.version,
          finalRating: v.finalRating ? Number(v.finalRating) : null,
          finalRepurchase: v.finalRepurchase || null,
          finalComment: v.finalComment,
        });
      if (m.type === "lot") {
        await api(m.record ? `/lots/${m.record.id}` : "/lots", m.record ? "PATCH" : "POST",
          { ...v, ...(m.record ? { version: m.record.version } : {}) }, m.record ? undefined : m.key);
      }
      if (m.type === "bag")
        await api(
          m.record ? `/bags/${m.record.id}` : "/bags",
          m.record ? "PATCH" : "POST",
          {
            ...v,
            massG: Number(v.massG),
            purchasedOn: v.purchasedOn || null,
            roastedOn: v.roastedOn || null,
            purchasePrice: v.purchasePrice || null,
            ...(m.record ? { version: m.record.version } : {}),
            ...(m.lotId ? { lotId: m.lotId } : {}),
          },
          m.record ? undefined : m.key,
        );
      if (m.type === "cup")
        await api("/consumptions", "POST", {
          bagId: m.bagId || null,
          profileId: m.profileId || null,
          clientEventId: m.key,
          volumeMl: Number(v.volumeMl),
          coffeeLabel: v.coffeeLabel || null,
        });
      if (m.type === "editCup") {
        const editedTime = new Date(`${v.consumedAt}+05:00`).toISOString();
        await api(`/consumptions/${m.event.id}`, "PATCH", {
          version: m.event.version,
          ...(editedTime !== new Date(`${localCupTime(m.event.consumed_at)}+05:00`).toISOString()
            ? { consumedAt: editedTime } : {}),
          volumeMl: Number(v.volumeMl),
          comment: v.comment,
          ...(m.newBagId !== undefined ? { bagId: m.newBagId || null } : {}),
          ...(m.newProfileId !== undefined ? { profileId: m.newProfileId || null } : {}),
          coffeeLabel: v.coffeeLabel || null,
        });
      }
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
        const rating = {
          overallRating: Number(v.overallRating),
          repurchase: v.repurchase || null,
          comment: v.comment,
          sensory,
          ...(v.tastedAt && (!m.tasting || new Date(`${v.tastedAt}+05:00`).getTime()
            !== new Date(`${localCupTime(m.tasting.tasted_at)}+05:00`).getTime())
            ? { tastedAt: new Date(`${v.tastedAt}+05:00`).toISOString() } : {}),
          preparationUncertain: v.preparationUncertain === "on",
          wishes: f.getAll("wishes"),
          ...(m.recipeChanged || m.tastingProfileId ? { brewSnapshot: readRecipe(f) } : {}),
          ...(m.tastingProfileId ? { profileId: m.tastingProfileId } : {}),
        };
        if (m.tasting) {
          await api(`/tastings/${m.tasting.id}`, "PATCH", { ...rating, version: m.tasting.version });
        } else {
          await api("/tastings", "POST", {
            ...rating,
            bagId: m.bagId,
            consumptionEventId: m.eventId || null,
            recordConsumption: v.recordConsumption === "on",
          }, m.key);
        }
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
                setQuickProfile("");
                setEditingProfile(null);
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
              {profiles.length > 1 && <label>Профиль для быстрого учёта
                <select value={selectedQuick.id ?? ""} onChange={e => setQuickProfile(e.target.value)}>
                  {profiles.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
                </select>
              </label>}
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
                      {b.photo_id ? <img loading="lazy" src={`/api/v1/media/${b.photo_id}?thumbnail=true`} alt={b.name} /> :
                      <span>
                        COFFEE
                        <br />
                        <b>{b.roaster_name || "ВАШ ЛОТ"}</b>
                        <em>{b.mass_g} г</em>
                      </span>}
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
                        onClick={() => quickCup({ bagId: b.id, profileId: selectedQuick.id, volumeMl: selectedQuick.default_cup_volume_ml, clientEventId: createId() })}
                      >
                        Выпил чашку · {selectedQuick.default_cup_volume_ml} мл
                      </button>
                      <SensorySummary summary={b.sensory_summary} />
                      <div className="card-actions">
                        <button onClick={() => open("photos", { record: b })}>Фото</button>
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
                              async () => {
                                const finished = await api(`/bags/${b.id}/status`, "POST", {
                                  status: "FINISHED",
                                  version: b.version,
                                });
                                open("finalImpression", { record: finished, name: b.name });
                              },
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
            <div className="filters">
              <label>Поиск по названию или обжарщику
                <input value={query} onChange={e => { setQuery(e.target.value); setOffset(0); }} />
              </label>
              <label>Сортировка
                <select value={historySort} onChange={e => { setHistorySort(e.target.value); setOffset(0); }}>
                  <option value="recent">Последнее использование</option>
                  <option value="name">По названию</option>
                  {kind !== "consumptions" && <option value="rating">По оценке</option>}
                </select>
              </label>
              {kind === "bags" && <label>Статус пачки
                <select value={historyStatus} onChange={e => { setHistoryStatus(e.target.value); setOffset(0); }}>
                  <option value="">Любой</option><option value="ACTIVE">Пью сейчас</option>
                  <option value="STOCK">В запасе</option><option value="FINISHED">Закончена</option>
                </select>
              </label>}
              {kind === "consumptions" ? <label>Известность лота
                <select value={coffee} onChange={e => { setCoffee(e.target.value); setOffset(0); }}>
                  <option value="">Все чашки</option><option value="known">Известный лот</option>
                  <option value="unknown">Неизвестный лот</option>
                </select>
              </label> : <label>Общая оценка{kind === "tastings" ? "" : " (округлённая)"}
                <select value={historyRating} onChange={e => { setHistoryRating(e.target.value); setOffset(0); }}>
                  <option value="">Любая</option>
                  {[1, 2, 3, 4, 5].map(value => <option key={value} value={value}>{value}</option>)}
                </select>
              </label>}
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
              <button onClick={() => {
                setQuery(""); setHistoryStatus(""); setHistoryRating(""); setCoffee("");
                setHistorySort("recent"); setDeleted(false); setOffset(0);
                setStart(kind === "consumptions" ? day() : "");
                setEnd(kind === "consumptions" ? day() : "");
              }}>Сбросить фильтры</button>
              {((start || end || kind === "consumptions") && (!start || !end || start > end)) ? <span>Укажите корректный период</span> : kind === "consumptions" && !deleted && <b>
                {stats.cups} чашек · {stats.volume} мл за период
              </b>}
            </div>
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
                    setStart(k === "consumptions" ? day() : "");
                    setEnd(k === "consumptions" ? day() : "");
                    setHistorySort("recent");
                    setOffset(0);
                  }}
                >
                  {n}
                </button>
              ))}
            </div>
            {kind === "lots" && !deleted && (
              <button className="primary" onClick={() => open("lot")}>Создать лот</button>
            )}
            <label className="check">
              <input
                type="checkbox"
                checked={deleted}
                onChange={(e) => { setDeleted(e.target.checked); setOffset(0); }}
              />
              Показать удалённые
            </label>
            {history.slice(0, 20).map((r) => (
              <article className="history-row" key={r.id}>
                <div>
                  <b>
                    {kind === "consumptions"
                      ? r.bag_deleted ? "Удалённая пачка" : r.bag_name || r.coffee_label || "Неизвестный кофе"
                      : r.name ||
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
                  {kind === "consumptions" && r.profile_snapshot?.name && <p>{r.profile_snapshot.name}</p>}
                  {r.comment && <p>{r.comment}</p>}
                  {kind === "lots" && <p>{r.rated_bags
                    ? `Рейтинг лота: ${sensoryNumber.format(r.rating)} из 5 · оценённых пачек: ${r.rated_bags}`
                    : "Ещё нет оценок"}</p>}
                  {kind === "bags" && <>
                    <p>{r.tasting_count ? `Средняя оценка чашек: ${sensoryNumber.format(r.rating)} из 5 · оценок: ${r.tasting_count}` : "Ещё нет оценок чашек"}</p>
                    {(r.final_rating != null || r.final_repurchase || r.final_comment) && <p>
                      Итоговое впечатление{r.final_rating != null && `: ${r.final_rating} из 5`}
                      {r.final_repurchase && ` · Повторная покупка: ${({ YES: "да", MAYBE: "возможно", NO: "нет" } as Row)[r.final_repurchase]}`}
                      {r.final_comment && <><br />{r.final_comment}</>}
                    </p>}
                  </>}
                  {kind === "tastings" && r.wishes?.length > 0 && <p>
                    {r.wishes.map((wish: keyof typeof tasteWishes) => tasteWishes[wish]).join(", ")}
                  </p>}
                  {kind === "bags" && <SensorySummary summary={r.sensory_summary} />}
                </div>
                <div className="row-actions">
                  {kind === "lots" && !deleted && (
                    <>
                      <button disabled={busy} onClick={() => open("lot", { record: r })}>Изменить лот</button>
                      <button disabled={busy} onClick={() => open("bag", { lotId: r.id, name: r.name })}>Добавить пачку</button>
                    </>
                  )}
                  {kind === "tastings" && !deleted && (
                    <button disabled={busy} onClick={() => open("tasting", { tasting: r })}>
                      Изменить
                    </button>
                  )}
                  {kind === "consumptions" && !deleted && (
                    <button disabled={busy} onClick={() => open("editCup", { event: r })}>
                      Изменить
                    </button>
                  )}
                  {kind === "consumptions" && !deleted && r.tasting_id && (
                    <button disabled={busy} onClick={() => run(() =>
                      api(`/consumptions/${r.id}/tasting-link?version=${r.version}`, "DELETE"))}>
                      Отвязать оценку
                    </button>
                  )}
                  {kind === "consumptions" && !deleted && r.bag_id && !r.bag_deleted && !r.tasting_id && (
                    <button disabled={busy} onClick={async () => {
                      try {
                        const ratings = await api(`/consumptions/${r.id}/eligible-tastings`);
                        open("linkTasting", { event: r, ratings });
                      } catch (failure) { setError((failure as Error).message); }
                    }}>Привязать оценку</button>
                  )}
                  {kind === "consumptions" &&
                    r.bag_id &&
                    !r.bag_deleted &&
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
                      <button onClick={() => open("photos", { record: r })}>Фото</button>
                      <button disabled={busy} onClick={() => open("finalImpression", { record: r, name: r.name })}>Итоговое впечатление</button>
                      <button disabled={busy} onClick={() => open("bag", { record: r, lotId: r.lot_id, name: r.name })}>Изменить пачку</button>
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
                disabled={history.length <= 20}
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
            <label>Редактируемый профиль
              <select value={editingProfile === "new" ? "new" : settingsProfile.id ?? ""}
                onChange={e => setEditingProfile(e.target.value)}>
                {profiles.map(item => <option key={item.id} value={item.id}>
                  {item.name}{item.id === profile.id ? " (по умолчанию)" : ""}
                </option>)}
                <option value="new">Создать профиль</option>
              </select>
            </label>
            {editingProfile !== "new" && settingsProfile.id !== profile.id && <>
              <button disabled={busy} onClick={() => run(async () => {
                await api("/profile/default-brew-profile", "PUT", { profileId: settingsProfile.id });
                setQuickProfile("");
              })}>Сделать профилем по умолчанию</button>
              <button disabled={busy} onClick={() => run(async () => {
                await api(`/brew-profiles/${settingsProfile.id}?version=${settingsProfile.version}`, "DELETE");
                setEditingProfile(null);
              })}>Архивировать профиль</button>
            </>}
            <form
              key={editingProfile === "new" ? "new" : `${settingsProfile.id}:${settingsProfile.version}`}
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                const recipe = Object.fromEntries([
                  ...["method", "milkSugar", ...recipeTexts.map(field => field.name)]
                    .map(name => [name, f.get(name) || null]),
                  ...recipeNumbers.map(({ name }) => {
                    const value = f.get(name);
                    return [name, value === "" ? null : Number(value)];
                  }),
                ]);
                run(async () => {
                  const creating = editingProfile === "new";
                  const saved = await api(creating ? "/brew-profiles" : `/brew-profiles/${settingsProfile.id}`, creating ? "POST" : "PATCH", {
                    name: f.get("name"),
                    defaultCupVolumeMl: Number(f.get("volume")),
                    version: settingsProfile.version,
                    grinder: f.get("grinder"),
                    brewer: f.get("brewer"),
                    recipe,
                  });
                  setEditingProfile(saved.id);
                });
              }}
            >
              <label>
                Название профиля
                <input
                  name="name"
                  defaultValue={editingProfile === "new" ? "" : settingsProfile.name}
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
                  defaultValue={editingProfile === "new" ? 250 : settingsProfile.default_cup_volume_ml}
                  required
                />
              </label>
              <p>
                Это объём напитка, который вы выпиваете, а не воды для
                заваривания. Старые записи сохранят свой объём.
              </p>
              <label>Кофемолка
                <input name="grinder" maxLength={200} defaultValue={editingProfile === "new" ? "" : settingsProfile.grinder ?? ""} />
              </label>
              <label>Кофеварка
                <input name="brewer" maxLength={200} defaultValue={editingProfile === "new" ? "" : settingsProfile.brewer ?? ""} />
              </label>
              <RecipeFields recipe={editingProfile === "new" ? undefined : settingsProfile.recipe} />
              <button className="primary" disabled={busy}>
                Сохранить настройки
              </button>
            </form>
            <h3>Часовой пояс</h3>
            <p>Asia/Yekaterinburg · UTC+5</p>
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
              {modal.type === "finalImpression" ? "Итоговое впечатление о пачке"
                : modal.type === "linkTasting" ? "Привязать оценку"
                : modal.type === "photos" ? "Фото пачки"
                : modal.type === "lot" ? (modal.record ? "Изменить лот" : "Новый лот")
                : modal.type === "bag"
                ? (modal.record ? "Изменить пачку" : "Новая пачка")
                : modal.type === "cup"
                  ? "Ещё одна чашка"
                  : modal.type === "editCup" ? "Изменить чашку"
                    : modal.tasting ? "Изменить оценку" : "Как вам кофе?"}
            </h2>
            {modal.name && <p>{modal.name}</p>}
            {modal.type === "photos" ? <BagPhotos bag={modal.record} committed={async () => {
              await refresh();
              setModal(null);
            }} /> : <form onSubmit={save}>
              {modal.type === "finalImpression" && <>
                <label>Итоговая оценка пачки
                  <select name="finalRating" defaultValue={modal.record.final_rating ?? ""}>
                    <option value="">Без оценки</option>
                    {[1, 2, 3, 4, 5].map(value => <option key={value} value={value}>{value} из 5</option>)}
                  </select>
                </label>
                <label>Купили бы снова?
                  <select name="finalRepurchase" defaultValue={modal.record.final_repurchase ?? ""}>
                    <option value="">Не указано</option><option value="YES">Да</option>
                    <option value="MAYBE">Возможно</option><option value="NO">Нет</option>
                  </select>
                </label>
                <label>Комментарий о пачке
                  <textarea name="finalComment" maxLength={5000} defaultValue={modal.record.final_comment ?? ""} />
                </label>
                <button type="button" onClick={() => setModal(null)} disabled={busy}>Пропустить</button>
              </>}
              {modal.type === "linkTasting" && (
                modal.ratings.length ? <label>Существующая оценка
                  <select name="tastingId" required defaultValue="">
                    <option value="" disabled>Выберите оценку</option>
                    {modal.ratings.map((rating: Row) => <option key={rating.id} value={rating.id}>
                      {format(rating.tasted_at)} · {rating.overall_rating}/5 {rating.comment?.slice(0, 80)}
                    </option>)}
                  </select>
                </label> : <p>У этой пачки пока нет свободных оценок.</p>
              )}
              {(modal.type === "lot" || (modal.type === "bag" && !modal.lotId)) && (
                    <>
                      <label>
                        Название кофе
                        <input
                          name="name"
                          defaultValue={modal.record?.name ?? ""}
                          required
                          maxLength={200}
                          autoFocus
                          placeholder="Например, Эфиопия Гуджи"
                        />
                      </label>
                      <label>
                        Обжарщик <small>необязательно</small>
                        <input name="roasterName" maxLength={120} defaultValue={modal.record?.roaster_name ?? ""} />
                      </label>
                    </>
              )}
              {modal.type === "bag" && (
                <>
                  <div className="form-row">
                    <label>
                      Масса, г
                      <input
                        name="massG"
                        type="number"
                        step="any"
                        min={1}
                        max={5000}
                        defaultValue={modal.record?.mass_g ?? 250}
                        required
                      />
                    </label>
                    {!modal.record && <label>
                      Статус
                      <select name="status">
                        <option value="ACTIVE">Пью сейчас</option>
                        <option value="STOCK">В запасе</option>
                      </select>
                    </label>}
                  </div>
                  <div className="form-row">
                    <label>Дата покупки
                      <input type="date" name="purchasedOn" max={day()}
                        value={modal.purchasedOn ?? modal.record?.purchased_on ?? ""}
                        onChange={e => setModal({ ...modal, purchasedOn: e.target.value })} />
                    </label>
                    <label>Дата обжарки
                      <input type="date" name="roastedOn" max={day()}
                        value={modal.roastedOn ?? modal.record?.roasted_on ?? ""}
                        onChange={e => setModal({ ...modal, roastedOn: e.target.value })} />
                    </label>
                  </div>
                  {(modal.purchasedOn ?? modal.record?.purchased_on) &&
                    (modal.roastedOn ?? modal.record?.roasted_on) >
                    (modal.purchasedOn ?? modal.record?.purchased_on) &&
                    <p role="status">Дата обжарки позже даты покупки. Проверьте даты.</p>}
                  <label>Цена покупки, {modal.record?.currency ?? "RUB"}
                    <input type="number" name="purchasePrice" min="0" step="any" defaultValue={modal.record?.purchase_price ?? ""} />
                  </label>
                  <label>
                    Заметка
                    <textarea name="note" maxLength={5000} defaultValue={modal.record?.note ?? ""} />
                  </label>
                </>
              )}
              {modal.type === "cup" && (
                <>
                  <label>Профиль чашки
                    <select value={modal.profileId} onChange={e => {
                      const id = e.target.value;
                      setModal({ ...modal, profileId: id, volumeMl: profiles.find(item => item.id === id)?.default_cup_volume_ml ?? 250 });
                    }}>
                      <option value="">Без профиля</option>
                      {profiles.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
                    </select>
                  </label>
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
                      key={modal.profileId}
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
              {modal.type === "editCup" && (
                <>
                  <label>Пачка чашки
                    <select disabled={!!modal.event.tasting_id}
                      value={modal.newBagId ?? modal.event.bag_id ?? ""}
                      onChange={e => setModal({ ...modal, newBagId: e.target.value })}>
                      <option value="">Неизвестный кофе</option>
                      {modal.event.bag_id && ![...bags, ...(modal.bagResults ?? [])].some(b => b.id === modal.event.bag_id) &&
                        <option value={modal.event.bag_id}>{modal.event.bag_name ?? "Удалённая пачка"}</option>}
                      {[...new Map<string, Row>([...bags, ...(modal.bagResults ?? [])]
                        .map((bag: Row) => [bag.id, bag])).values()].map(bag => (
                        <option key={bag.id} value={bag.id}>{bag.name} · {bag.status} · {format(bag.created_at)}</option>
                      ))}
                    </select>
                  </label>
                  {modal.event.tasting_id ? <p>Для смены пачки сначала отвяжите оценку.</p> :
                    <label>Найти пачку по названию или обжарщику
                      <input onChange={async e => {
                        const query = e.target.value;
                        const key = modal.key;
                        setModal(current => current?.key === key ? { ...current, bagQuery: query } : current);
                        try {
                          const results = await api("/bags?limit=100&q=" + encodeURIComponent(query));
                          setModal(current => current?.key === key && current?.bagQuery === query
                            ? { ...current, bagResults: results } : current);
                        } catch (failure) { setError((failure as Error).message); }
                      }} />
                    </label>}
                  {!(modal.newBagId ?? modal.event.bag_id) && <label>Что пили?
                    <input name="coffeeLabel" maxLength={200} defaultValue={modal.event.coffee_label ?? ""} />
                  </label>}
                  <label>Профиль чашки
                    <select defaultValue="keep" onChange={e =>
                      setModal({ ...modal, newProfileId: e.target.value === "keep" ? undefined : e.target.value })}>
                      <option value="keep">Оставить снимок: {modal.event.profile_snapshot?.name ?? "без профиля"}</option>
                      <option value="">Без профиля</option>
                      {profiles.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
                    </select>
                  </label>
                  <label>
                    Дата и время (Екатеринбург)
                    <input name="consumedAt" type="datetime-local" step="1"
                      defaultValue={localCupTime(modal.event.consumed_at)} required />
                  </label>
                  <label>
                    Объём напитка, мл
                    <input name="volumeMl" type="number" min={10} max={2000}
                      defaultValue={modal.event.volume_ml} required />
                  </label>
                  <label>
                    Заметка
                    <textarea name="comment" maxLength={5000} defaultValue={modal.event.comment ?? ""} />
                  </label>
                </>
              )}
              {modal.type === "tasting" && (
                <>
                  <label>
                    Общее впечатление
                    <select name="overallRating" required defaultValue={modal.tasting?.overall_rating ?? ""}>
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
                    <select name="repurchase" defaultValue={modal.tasting?.repurchase ?? ""}>
                      <option value="">Пока не знаю</option>
                      <option value="YES">Да</option>
                      <option value="MAYBE">Возможно</option>
                      <option value="NO">Нет</option>
                    </select>
                  </label>
                  <details open={modal.detailed ?? localStorage.getItem("tasting-detailed") === "true"}
                    onToggle={e => {
                      const detailed = e.currentTarget.open;
                      if (detailed !== (modal.detailed ?? localStorage.getItem("tasting-detailed") === "true")) {
                        localStorage.setItem("tasting-detailed", String(detailed));
                        setModal(current => current?.key === modal.key ? { ...current, detailed } : current);
                      }
                    }}>
                    <summary>Подробнее о вкусе · 6 шкал</summary>
                    <p className="muted">
                      Интенсивность ощущения, а не оценка качества: от 1 (не
                      ощущаю / слабая) до 5 (очень выраженная).
                    </p>
                    {Object.entries(dimensions).map(([k, n]) => (
                      <label key={k}>
                        {n}
                        <select name={k} defaultValue={
                          modal.tasting?.sensory?.[k]?.status === "UNKNOWN"
                            ? "UNKNOWN" : modal.tasting?.sensory?.[k]?.value ?? ""
                        }>
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
                    <label>Профиль приготовления
                      <select value={modal.tastingProfileId ?? ""} onChange={e => {
                        setModal({ ...modal, tastingProfileId: e.target.value, recipeChanged: false });
                      }}>
                        <option value="">{modal.tasting ? (modal.tasting.brew_snapshot?.profileName ?? "Сохранённый профиль") : "Профиль по умолчанию"}</option>
                        {profiles.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
                      </select>
                    </label>
                    <div onChange={() => setModal(current => current ? {...current, recipeChanged: true} : current)}>
                      <RecipeFields title="Рецепт этой чашки"
                        key={`${modal.tastingProfileId ?? ""}:${JSON.stringify(modal.lastGrind ?? {})}`}
                        recipe={(() => {
                          const selected = profiles.find(item => item.id === modal.tastingProfileId);
                          if (!selected && modal.tasting) return modal.tasting.brew_snapshot;
                          const recipe = { ...(selected ?? profile).recipe };
                          if (modal.lastGrind?.grindClicks != null || modal.lastGrind?.grind) {
                            delete recipe.grindClicks;
                            delete recipe.grind;
                            Object.assign(recipe, modal.lastGrind);
                          }
                          return recipe;
                        })()} />
                    </div>
                    <fieldset><legend>Пожелания к вкусу</legend>
                      {Object.entries(tasteWishes).map(([value, label]) => (
                        <label className="check" key={value}><input type="checkbox" name="wishes" value={value}
                          defaultChecked={modal.tasting?.wishes?.includes(value)} />{label}</label>
                      ))}
                    </fieldset>
                  </details>
                  <label>Дата и время оценки (Екатеринбург)
                    <input name="tastedAt" type="datetime-local" step="1"
                      defaultValue={modal.tasting ? localCupTime(modal.tasting.tasted_at) : ""} />
                  </label>
                  <label className="check"><input name="preparationUncertain" type="checkbox"
                    defaultChecked={modal.tasting?.preparation_uncertain} />Не уверен в приготовлении</label>
                  <label>
                    Ваши впечатления
                    <textarea name="comment" maxLength={5000} defaultValue={modal.tasting?.comment ?? ""} />
                  </label>
                  {modal.tasting ? null : modal.eventId ? (
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
              <button className="primary wide" disabled={busy || (modal.type === "linkTasting" && !modal.ratings.length)}>
                {busy ? "Сохраняем…" : "Сохранить"}
              </button>
            </form>}
          </section>
        </div>
      )}
    </div>
  );
}
