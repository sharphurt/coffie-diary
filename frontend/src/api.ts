let csrf: { token: string; headerName: string } | null = null;
export async function refreshCsrf() {
  csrf = await fetch("/api/v1/auth/csrf").then((r) => r.json());
}
export async function api(
  path: string,
  method = "GET",
  data?: unknown,
  key?: string,
) {
  if (!csrf) await refreshCsrf();
  const headers: Record<string, string> = {};
  if (method !== "GET" && csrf) headers[csrf.headerName] = csrf.token;
  if (key) headers["Idempotency-Key"] = key;
  if (data && !(data instanceof FormData)) headers["Content-Type"] = "application/json";
  const r = await fetch("/api/v1" + path, {
    method,
    headers,
    body: data instanceof FormData ? data : data ? JSON.stringify(data) : undefined,
  });
  if (!r.ok) {
    let e;
    try {
      e = await r.json();
    } catch {
      e = {};
    }
    throw new Error(
      e.message ||
        (r.status === 401
          ? "Войдите в дневник"
          : "Не удалось сохранить. Данные формы сохранены — попробуйте ещё раз."),
    );
  }
  return r.status === 204 ? null : r.json();
}
export async function login(username: string, password: string) {
  await refreshCsrf();
  const r = await fetch("/api/v1/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      [csrf!.headerName]: csrf!.token,
    },
    body: new URLSearchParams({ username, password }),
  });
  if (!r.ok) throw new Error("Неверный логин или пароль");
  await refreshCsrf();
}
