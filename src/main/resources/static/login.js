const form = document.querySelector("#login-form");
let session;
async function refresh() {
  const response = await fetch("/api/session", { cache: "no-store" });
  if (!response.ok) throw new Error("서버 연결을 확인하세요.");
  session = await response.json();
  if (session.username) location.replace("/");
}
refresh().catch(
  (error) =>
    (document.querySelector("#login-error").textContent = error.message),
);
form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = form.querySelector("button");
  button.disabled = true;
  try {
    await refresh();
    const response = await fetch("/api/login", {
      method: "POST",
      headers: { [session.csrfHeader]: session.csrf },
      body: new URLSearchParams(new FormData(form)),
    });
    if (!response.ok) throw new Error("아이디 또는 비밀번호를 확인하세요.");
    location.replace("/");
  } catch (error) {
    document.querySelector("#login-error").textContent = error.message;
  } finally {
    button.disabled = false;
  }
});
