import React, { useEffect, useMemo, useState } from "react";
import { MapContainer, TileLayer, GeoJSON, useMap } from "react-leaflet";
import L from "leaflet";
import {
  AlertTriangle,
  ArrowRight,
  Bell,
  Check,
  Clock3,
  CloudRain,
  Layers3,
  MapPinned,
  RefreshCw,
  ShieldAlert,
  X,
} from "lucide-react";

const DISCLAIMER =
  "Forecast-based guidance. Follow official Rwanda Emergency Management Agency advice.";
const colors = {
  HIGH: "#cf584f",
  MODERATE: "#e2ad4c",
  LOW: "#79a48b",
  UNKNOWN: "#c6c8bf",
};
const api = async (path, options = {}) => {
  const r = await fetch("/api/v1" + path, options);
  if (!r.ok) throw new Error((await r.text()).slice(0, 160));
  return r.status === 204 ? null : r.json();
};
const date = (value) =>
  value
    ? new Date(value).toLocaleString("en-RW", {
        dateStyle: "medium",
        timeStyle: "short",
      })
    : "Awaiting first assessment";
const parse = (value) => {
  try {
    return typeof value === "string" ? JSON.parse(value) : value;
  } catch {
    return null;
  }
};

function FitBounds({ sectors }) {
  const map = useMap();
  useEffect(() => {
    let bounds = L.latLngBounds([]);
    sectors.forEach((s) => {
      const g = parse(s.boundary);
      if (g) bounds.extend(L.geoJSON(g).getBounds());
    });
    if (bounds.isValid()) map.fitBounds(bounds.pad(0.08), { animate: false });
  }, [map, sectors]);
  return null;
}
function RiskMap({ sectors, selected, onSelect, roads = [] }) {
  const features = useMemo(
    () => ({
      type: "FeatureCollection",
      features: sectors
        .map((s) => ({
          type: "Feature",
          properties: {
            id: s.id,
            name: s.name,
            score: s.score,
            level: s.level,
          },
          geometry: parse(s.boundary),
        }))
        .filter((f) => f.geometry),
    }),
    [sectors],
  );
  const impact = useMemo(
    () => ({
      type: "FeatureCollection",
      features: roads
        .map((r) => ({
          type: "Feature",
          properties: { name: r.name },
          geometry: parse(r.geometry),
        }))
        .filter((f) => f.geometry),
    }),
    [roads],
  );
  if (!sectors.length)
    return (
      <div className="empty-map">
        <MapPinned size={36} />
        <h2>Risk map is not available yet</h2>
        <p>
          Sector data will appear here after the operator imports Kigali
          boundaries.
        </p>
      </div>
    );
  return (
    <MapContainer
      center={[-1.955, 30.075]}
      zoom={11}
      zoomControl={false}
      className="map"
    >
      <TileLayer
        attribution="&copy; OpenStreetMap contributors"
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <FitBounds sectors={sectors} />
      <GeoJSON
        key={JSON.stringify(
          features.features.map((f) => [
            f.properties.id,
            f.properties.level,
            selected,
          ]),
        )}
        data={features}
        style={(f) => ({
          color: f.properties.id === selected ? "#102a40" : "#526979",
          weight: f.properties.id === selected ? 2.5 : 1,
          fillColor: colors[f.properties.level || "UNKNOWN"],
          fillOpacity: 0.55,
        })}
        onEachFeature={(f, layer) => {
          layer.bindTooltip(
            `${f.properties.name} · ${f.properties.level || "No assessment"}`,
            { sticky: true },
          );
          layer.on("click", () => onSelect(f.properties.id));
        }}
      />
      {impact.features.length > 0 && (
        <GeoJSON
          key={selected + "roads"}
          data={impact}
          style={{ color: "#b8382e", weight: 4, opacity: 0.85 }}
          onEachFeature={(f, layer) => layer.bindTooltip(f.properties.name)}
        />
      )}
    </MapContainer>
  );
}
function RainChart({ forecast }) {
  const h = parse(forecast?.hourly_forecast);
  const values = h?.precipitation?.slice(0, 12) || [];
  const max = Math.max(1, ...values.filter(Number.isFinite));
  return (
    <div
      className="rain-chart"
      role="img"
      aria-label="Rain forecast for the next 12 hours"
    >
      {values.length ? (
        values.map((v, i) => (
          <div className="rain-col" key={i}>
            <span
              style={{ height: `${Math.max(3, ((v || 0) / max) * 88)}%` }}
              title={`${v || 0} mm`}
            />
            <small>{i + 1}h</small>
          </div>
        ))
      ) : (
        <p className="quiet">Forecast unavailable</p>
      )}
    </div>
  );
}
function Detail({ sector, forecast, roads }) {
  if (!sector)
    return (
      <div className="detail-empty">
        <MapPinned size={28} />
        <h2>Select a sector</h2>
        <p>Choose an area on the map to inspect its latest risk assessment.</p>
      </div>
    );
  return (
    <>
      <div className="detail-head">
        <span className="section-label">Selected sector</span>
        <h2>{sector.name}</h2>
        <p>{sector.district} District</p>
      </div>
      <div className="risk-line">
        <span
          className={"risk-pill " + (sector.level || "UNKNOWN").toLowerCase()}
        >
          {sector.level || "Awaiting data"}
        </span>
        <strong>
          {sector.score == null ? "—" : sector.score}
          <small> / 100</small>
        </strong>
      </div>
      <p className="score-note">
        Experimental risk index · Updated {date(sector.assessed_at)}
      </p>
      <div className="panel-section">
        <div className="panel-title">
          <h3>Hourly rainfall</h3>
          <span>Next 12 hours</span>
        </div>
        <RainChart forecast={forecast} />
        <div className="detail-facts">
          <span>
            12h total{" "}
            <b>{forecast?.forecast_rainfall_mm?.toFixed?.(1) ?? "—"} mm</b>
          </span>
          <span>
            Soil moisture{" "}
            <b>{forecast?.soil_moisture?.toFixed?.(2) ?? "—"} m³/m³</b>
          </span>
        </div>
      </div>
      <div className="panel-section">
        <div className="panel-title">
          <h3>Flagged roads</h3>
          <span>{roads.length} segments</span>
        </div>
        {roads.length ? (
          <ul className="road-list">
            {roads.map((r) => (
              <li key={r.id}>
                <AlertTriangle size={15} />
                <span>{r.name}</span>
                <small>
                  {r.vulnerability == null
                    ? "Unverified"
                    : `${Math.round(r.vulnerability * 100)}% exposure`}
                </small>
              </li>
            ))}
          </ul>
        ) : (
          <p className="quiet">
            No verified road impacts available for this assessment.
          </p>
        )}
      </div>
    </>
  );
}
function Officer({
  sectors,
  selected,
  setSelected,
  forecast,
  roads,
  alerts,
  reload,
  auth,
  setAuth,
}) {
  const [tab, setTab] = useState("map"),
    [message, setMessage] = useState("");
  const current = sectors.find((s) => s.id === selected);
  const admin = async (path) => {
    try {
      setMessage("Working…");
      await api(path, {
        method: "POST",
        headers: { Authorization: `Basic ${auth}` },
      });
      setMessage("Done. Refreshing data…");
      reload();
    } catch (e) {
      setMessage(e.message);
    }
  };
  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-mark">
          <Layers3 size={25} />
          <span>FloodGuard</span>
        </div>
        <span className="header-rule" />
        <strong>Kigali risk map</strong>
        <nav>
          <button
            className={tab === "map" ? "active" : ""}
            onClick={() => setTab("map")}
          >
            Map
          </button>
          <button
            className={tab === "alerts" ? "active" : ""}
            onClick={() => setTab("alerts")}
          >
            Alerts
          </button>
          <button
            className={tab === "admin" ? "active" : ""}
            onClick={() => setTab("admin")}
          >
            Admin
          </button>
        </nav>
        <button className="text-button" onClick={reload} aria-label="Refresh">
          <RefreshCw size={17} />
        </button>
        <button
          className="text-button"
          onClick={() => {
            setAuth("");
            sessionStorage.removeItem("floodguard-auth");
          }}
        >
          Sign out
        </button>
      </header>
      {tab === "map" ? (
        <main className="workspace">
          <section className="map-board">
            <div className="map-heading">
              <div>
                <span className="section-label">City of Kigali</span>
                <h1>Flood risk monitoring map</h1>
              </div>
              <span className="source-chip">
                <CloudRain size={14} /> Open-Meteo · experimental model
              </span>
            </div>
            <div className="map-frame">
              {sectors.length ? (
                <RiskMap
                  sectors={sectors}
                  selected={selected}
                  onSelect={setSelected}
                  roads={roads}
                />
              ) : (
                <div className="empty-map">
                  <MapPinned size={36} />
                  <h2>Sector map is ready to import</h2>
                  <p>
                    Use Admin to load Kigali boundaries from Rwanda's public
                    boundary service.
                  </p>
                </div>
              )}
              <div className="map-legend">
                <strong>Forecast risk</strong>
                {["HIGH", "MODERATE", "LOW", "UNKNOWN"].map((l) => (
                  <span key={l}>
                    <i style={{ background: colors[l] }} />
                    {l === "UNKNOWN" ? "No assessment" : l.toLowerCase()}
                  </span>
                ))}
              </div>
            </div>
            <div className="map-footer">
              <span>Sector boundaries: Rwanda public GIS service</span>
              <span>{sectors.length} Kigali sectors</span>
            </div>
          </section>
          <aside className="side-panel">
            <Detail sector={current} forecast={forecast} roads={roads} />
            <div className="panel-section alert-preview">
              <div className="panel-title">
                <h3>Alert feed</h3>
                <button onClick={() => setTab("alerts")}>
                  View all <ArrowRight size={14} />
                </button>
              </div>
              {alerts.slice(0, 3).map((a) => (
                <div className="feed-item" key={a.id}>
                  <i className={(a.level || "").toLowerCase()} />
                  <span>
                    <b>{a.sector_name}</b>
                    <small>
                      {a.level} · {date(a.triggered_at)}
                    </small>
                  </span>
                </div>
              ))}
              {!alerts.length && (
                <p className="quiet">
                  No threshold alerts in the last 24 hours.
                </p>
              )}
            </div>
          </aside>
        </main>
      ) : tab === "alerts" ? (
        <main className="subpage">
          <h1>Alert activity</h1>
          <p>Threshold crossings in the last 24 hours.</p>
          <div className="list-panel">
            {alerts.map((a) => (
              <div className="alert-row" key={a.id}>
                <span className={"risk-pill " + a.level.toLowerCase()}>
                  {a.level}
                </span>
                <strong>{a.sector_name}</strong>
                <span>{a.sent_count} SMS sent</span>
                <time>{date(a.triggered_at)}</time>
              </div>
            ))}
            {!alerts.length && <p className="quiet">No alert events yet.</p>}
          </div>
        </main>
      ) : (
        <main className="subpage">
          <h1>Operations</h1>
          <p>
            Load official boundaries and trigger the first weather assessment.
            Road vulnerability and verified shelter data must be supplied before
            operational use.
          </p>
          <div className="admin-actions">
            <button onClick={() => admin("/admin/import/boundaries")}>
              Import Kigali boundaries
            </button>
            <button onClick={() => admin("/admin/ingest")}>
              Run weather ingestion
            </button>
          </div>
          <p role="status">{message}</p>
          <OperationsPanel sectors={sectors} auth={auth} />
          <div className="list-panel">
            <h2>Sector thresholds</h2>
            {sectors.map((s) => (
              <Threshold key={s.id} sector={s} auth={auth} onSaved={reload} />
            ))}
          </div>
        </main>
      )}
      <footer className="safety">
        <ShieldAlert size={18} />
        {DISCLAIMER}
      </footer>
    </div>
  );
}
function Threshold({ sector, auth, onSaved }) {
  const [m, setM] = useState(sector.moderate_threshold),
    [h, setH] = useState(sector.high_threshold),
    [msg, setMsg] = useState("");
  return (
    <form
      className="threshold-row"
      onSubmit={async (e) => {
        e.preventDefault();
        try {
          await api(`/admin/sectors/${sector.id}/thresholds`, {
            method: "PUT",
            headers: {
              Authorization: `Basic ${auth}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({ moderate: Number(m), high: Number(h) }),
          });
          setMsg("Saved");
          onSaved();
        } catch (ex) {
          setMsg(ex.message);
        }
      }}
    >
      <strong>{sector.name}</strong>
      <label>
        Moderate{" "}
        <input
          type="number"
          min="1"
          max="98"
          value={m}
          onChange={(e) => setM(e.target.value)}
        />
      </label>
      <label>
        High{" "}
        <input
          type="number"
          min="2"
          max="100"
          value={h}
          onChange={(e) => setH(e.target.value)}
        />
      </label>
      <button>Save</button>
      <small role="status">{msg}</small>
    </form>
  );
}
function OperationsPanel({ sectors, auth }) {
  const [summary, setSummary] = useState(null),
    [day, setDay] = useState(new Date().toISOString().slice(0, 10)),
    [from, setFrom] = useState("2023-05-01"),
    [to, setTo] = useState("2023-05-03"),
    [sector, setSector] = useState(""),
    [report, setReport] = useState(null),
    [error, setError] = useState("");
  useEffect(() => {
    api("/officer/summary", { headers: { Authorization: `Basic ${auth}` } })
      .then(setSummary)
      .catch(() => {});
  }, [auth]);
  useEffect(() => {
    if (!sector && sectors.length) setSector(sectors[0].id);
  }, [sectors, sector]);
  const get = async (path) => {
    setError("");
    try {
      setReport(
        await api(path, { headers: { Authorization: `Basic ${auth}` } }),
      );
    } catch (ex) {
      setError(
        "Could not load the requested history. Check the dates and try again.",
      );
    }
  };
  return (
    <>
      <div className="list-panel">
        <h2>Delivery status</h2>
        <p>{summary?.subscriberCount ?? "—"} active SMS subscriptions</p>
        {summary?.deliveryRecords?.length ? (
          <div className="delivery-list">
            {summary.deliveryRecords.slice(0, 20).map((r) => (
              <div key={r.id}>
                <span>{r.status}</span>
                <time>{date(r.updated_at)}</time>
              </div>
            ))}
          </div>
        ) : (
          <p className="quiet">No SMS delivery records yet.</p>
        )}
      </div>
      <div className="list-panel">
        <h2>History and backtest</h2>
        <div className="history-tools">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              get(`/officer/replay?date=${day}`);
            }}
          >
            <label>
              Replay date
              <input
                type="date"
                value={day}
                onChange={(e) => setDay(e.target.value)}
              />
            </label>
            <button>View stored events</button>
          </form>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (sector)
                get(
                  `/officer/backtest?sectorId=${sector}&from=${from}&to=${to}`,
                );
            }}
          >
            <label>
              Sector
              <select
                value={sector}
                onChange={(e) => setSector(e.target.value)}
              >
                {sectors.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              From
              <input
                type="date"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
              />
            </label>
            <label>
              To
              <input
                type="date"
                value={to}
                onChange={(e) => setTo(e.target.value)}
              />
            </label>
            <button>Run experimental hindcast</button>
          </form>
        </div>
        {error && <p role="alert">{error}</p>}
        {report && (
          <div className="history-result">
            <p>
              {report.method ||
                `${report.assessments?.length || 0} assessments and ${report.alerts?.length || 0} alerts stored for ${report.date}`}
            </p>
            {report.series && (
              <div
                className="series-bars"
                role="img"
                aria-label="Hindcast risk index over selected period"
              >
                {report.series.map((x, i) => (
                  <span
                    key={i}
                    title={`${x.time}: ${x.score}`}
                    style={{
                      height: `${Math.max(2, x.score)}%`,
                      background: colors[x.level],
                    }}
                  />
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </>
  );
}
function Public({ sectors, selected, setSelected, forecast }) {
  const [phone, setPhone] = useState(""),
    [state, setState] = useState("");
  const current = sectors.find((s) => s.id === selected);
  return (
    <div className="public-shell">
      <header className="public-header">
        <div className="brand-mark">
          <Layers3 size={24} />
          <span>FloodGuard</span>
        </div>
        <span>Kigali flood risk</span>
        <a href="/operator">
          Officer dashboard <ArrowRight size={15} />
        </a>
      </header>
      <main>
        <div className="public-intro">
          <span className="section-label">Current conditions</span>
          <h1>Check flood risk in your sector</h1>
          <p>
            See the latest forecast-based risk assessment for Kigali. Select
            your sector to view its status.
          </p>
        </div>
        <label className="sector-picker">
          Your sector
          <select
            value={selected || ""}
            onChange={(e) => setSelected(e.target.value)}
          >
            <option value="">Choose a sector</option>
            {sectors.map((s) => (
              <option value={s.id} key={s.id}>
                {s.name} · {s.district}
              </option>
            ))}
          </select>
        </label>
        {current && (
          <div className="public-result">
            <div>
              <span className="section-label">{current.name}</span>
              <h2>
                {current.level
                  ? `${current.level.toLowerCase()} risk`
                  : "Assessment pending"}
              </h2>
              <p>
                Risk index {current.score ?? "—"} / 100 ·{" "}
                {date(current.assessed_at)}
              </p>
            </div>
            <span
              className={
                "risk-pill " + (current.level || "UNKNOWN").toLowerCase()
              }
            >
              {current.level || "No data"}
            </span>
          </div>
        )}
        <section className="public-map">
          <RiskMap
            sectors={sectors}
            selected={selected}
            onSelect={setSelected}
          />
        </section>
        <section className="signup">
          <div>
            <Bell size={23} />
            <h2>Get SMS warnings</h2>
            <p>
              Register for High risk alerts in your sector. Reply STOP to any
              message to unsubscribe.
            </p>
          </div>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              setState("Registering…");
              try {
                await api("/subscriptions", {
                  method: "POST",
                  headers: { "Content-Type": "application/json" },
                  body: JSON.stringify({ sectorId: selected, phone }),
                });
                setState(
                  "Registered. A confirmation SMS will arrive if delivery is configured.",
                );
              } catch (ex) {
                setState(
                  "Registration failed. Check the sector and phone number.",
                );
              }
            }}
          >
            <label>
              Phone number
              <input
                type="tel"
                placeholder="+2507…"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                required
              />
            </label>
            <button disabled={!selected}>Register for alerts</button>
            <p role="status">{state}</p>
          </form>
        </section>
      </main>
      <footer className="safety">
        <ShieldAlert size={18} />
        {DISCLAIMER}
        <span>Risk index is experimental and not a flood probability.</span>
      </footer>
    </div>
  );
}
function Login({ setAuth }) {
  const [name, setName] = useState("admin"),
    [password, setPassword] = useState(""),
    [error, setError] = useState("");
  return (
    <div className="login-page">
      <div className="login-card">
        <div className="brand-mark">
          <Layers3 size={26} />
          <span>FloodGuard</span>
        </div>
        <h1>Officer access</h1>
        <p>Sign in to monitor Kigali risk and manage sector data.</p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const token = btoa(`${name}:${password}`);
            try {
              await api("/officer/session", {
                headers: { Authorization: `Basic ${token}` },
              });
              sessionStorage.setItem("floodguard-auth", token);
              setAuth(token);
            } catch {
              setError("Sign-in failed. Check your credentials.");
            }
          }}
        >
          <label>
            Username
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoComplete="username"
            />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </label>
          <button>Sign in</button>
          <p role="alert">{error}</p>
        </form>
        <a href="/">
          View public risk map <ArrowRight size={15} />
        </a>
      </div>
    </div>
  );
}
function SecureOfficer(props) {
  const [checking, setChecking] = useState(true),
    [change, setChange] = useState(false);
  useEffect(() => {
    api("/officer/session", {
      headers: { Authorization: `Basic ${props.auth}` },
    })
      .then((data) => {
        setChange(!!data.mustChangePassword);
        setChecking(false);
      })
      .catch(() => {
        sessionStorage.removeItem("floodguard-auth");
        props.setAuth("");
        setChecking(false);
      });
  }, [props.auth]);
  if (checking)
    return <div className="login-page">Checking officer access…</div>;
  if (change)
    return (
      <PasswordChange
        auth={props.auth}
        setAuth={props.setAuth}
        done={() => setChange(false)}
      />
    );
  return <Officer {...props} />;
}
function PasswordChange({ auth, setAuth, done }) {
  const [current, setCurrent] = useState(""),
    [next, setNext] = useState(""),
    [error, setError] = useState("");
  return (
    <div className="login-page">
      <div className="login-card">
        <div className="brand-mark">
          <Layers3 size={26} />
          <span>FloodGuard</span>
        </div>
        <h1>Set a new password</h1>
        <p>Change the initial officer password before using the dashboard.</p>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await api("/officer/password", {
                method: "PUT",
                headers: {
                  Authorization: `Basic ${auth}`,
                  "Content-Type": "application/json",
                },
                body: JSON.stringify({
                  currentPassword: current,
                  newPassword: next,
                }),
              });
              const username = atob(auth).split(":")[0];
              const updated = btoa(`${username}:${next}`);
              sessionStorage.setItem("floodguard-auth", updated);
              setAuth(updated);
              done();
            } catch (ex) {
              setError(
                "Password change failed. Check the current password and use at least 14 characters.",
              );
            }
          }}
        >
          <label>
            Current password
            <input
              type="password"
              value={current}
              onChange={(e) => setCurrent(e.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          <label>
            New password
            <input
              type="password"
              minLength="14"
              value={next}
              onChange={(e) => setNext(e.target.value)}
              autoComplete="new-password"
              required
            />
          </label>
          <button>Change password</button>
          <p role="alert">{error}</p>
        </form>
      </div>
    </div>
  );
}
export default function App() {
  const [sectors, setSectors] = useState([]),
    [alerts, setAlerts] = useState([]),
    [selected, setSelected] = useState(""),
    [forecast, setForecast] = useState(null),
    [roads, setRoads] = useState([]),
    [auth, setAuth] = useState(sessionStorage.getItem("floodguard-auth") || "");
  const [error, setError] = useState("");
  const operator = location.pathname.startsWith("/operator");
  const reload = () => {
    api("/sectors")
      .then((data) => {
        setSectors(data);
        setSelected(
          (old) =>
            old ||
            (operator
              ? data.find((s) => s.name === "Muhima")?.id || data[0]?.id
              : "") ||
            "",
        );
        setError("");
      })
      .catch(() => setError("Service unavailable. Try again shortly."));
    api("/alerts")
      .then(setAlerts)
      .catch(() => {});
  };
  useEffect(() => {
    reload();
    const timer = setInterval(reload, 60000);
    const ws = new WebSocket(
      `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/risk`,
    );
    ws.onmessage = reload;
    return () => {
      clearInterval(timer);
      ws.close();
    };
  }, []);
  useEffect(() => {
    if (!selected) return;
    api(`/sectors/${selected}/forecast`)
      .then(setForecast)
      .catch(() => setForecast(null));
    api(`/sectors/${selected}/roads/impact`)
      .then(setRoads)
      .catch(() => setRoads([]));
  }, [selected, sectors]);
  return (
    <>
      {error && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}
      {operator ? (
        auth ? (
          <SecureOfficer
            {...{
              sectors,
              selected,
              setSelected,
              forecast,
              roads,
              alerts,
              reload,
              auth,
              setAuth,
            }}
          />
        ) : (
          <Login setAuth={setAuth} />
        )
      ) : (
        <Public {...{ sectors, selected, setSelected, forecast }} />
      )}
    </>
  );
}
