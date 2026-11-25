self.addEventListener("push", (event) => {
  let notification = {};
  try {
    notification = event.data ? event.data.json() : {};
  } catch {
    notification = {
      body: event.data?.text() || "A new flood risk alert is available.",
    };
  }
  event.waitUntil(
    self.registration.showNotification(
      notification.title || "FloodGuard alert",
      {
        body:
          notification.body || "Check the latest flood risk in your sector.",
        tag: "floodguard-risk",
        data: { url: notification.url || "/" },
      },
    ),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL(
    event.notification.data?.url || "/",
    self.location.origin,
  );
  event.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then(async (clients) => {
        const existing = clients.find((client) =>
          client.url.startsWith(self.location.origin),
        );
        if (existing) {
          await existing.focus();
          return;
        }
        await self.clients.openWindow(target.href);
      }),
  );
});
