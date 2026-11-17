"use strict";
const environments = {UNSPECIFIED: "미지정", PRODUCTION: "운영", STAGING: "검증", DEVELOPMENT: "개발", TEST: "테스트"};
const lifecycles = {ACTIVE: "운영", MAINTENANCE: "유지보수", RETIRED: "폐기·사용 종료"};
const serviceTypes = {OTHER: "미분류", MIDDLEWARE: "미들웨어", APPLICATION: "애플리케이션", COMMON: "공통·보안·백업", WEB: "웹 서버", DATABASE: "데이터베이스", CONTAINER: "컨테이너", CACHE: "캐시", MESSAGING: "메시징", INFRASTRUCTURE: "인프라"};
let assetQuery = "", assetEnvironment = "", assetFilter = "", assetDetail = null;
let serviceQuery = "", serviceFilter = "active", serviceColumns = "operations", excludeSystemServices = false, editingService = null;
const assetLink = id => "#assets/" + encodeURIComponent(id);
const options = (values, value) => Object.entries(values).map(([key, label]) => `<option value="${esc(key)}" ${key === value ? "selected" : ""}>${esc(label)}</option>`).join("");
const filled = value => value ? esc(value) : '<span class="muted">미입력</span>';
const memory = bytes => bytes > 0 ? (bytes / 1024 ** 3).toLocaleString("ko-KR", {maximumFractionDigits: 1}) + " GiB" : "미수집";
const assetTime = value => value ? `<time datetime="${esc(value)}" title="${date(value)}">${esc(new Date(value).toLocaleString("sv-SE", {year:"numeric", month:"2-digit", day:"2-digit", hour:"2-digit", minute:"2-digit"}))}</time>` : "관측 이력 없음";

function assetKpis(summary) {
  return `<div class="kpis">
    <article class="kpi"><p>등록 자산</p><strong>${number(summary.total)}</strong><small>점검 전 자산 ${number(summary.unscanned)}대</small></article>
    <article class="kpi"><p>서비스 후보·등록</p><strong>${number(summary.services)}</strong><small>보관 처리한 항목 제외</small></article>
    <article class="kpi ${summary.undocumented ? "alert" : ""}"><p>운영 절차 보완</p><strong>${number(summary.undocumented)}</strong><small>시작·종료·상태 확인 방법 미입력</small></article>
    <article class="kpi ${summary.findings ? "alert" : ""}"><p>자산별 최근 발견·의심</p><strong>${number(summary.findings)}</strong><small>자산마다 최근 완료 점검 1회 기준</small></article>
  </div>${summary.osUpgrade ? `<div class="os-upgrade-notice" role="status"><div><strong>OS 업그레이드 필요 ${number(summary.osUpgrade)}대</strong><span>지원 종료 또는 정규 유지보수가 종료된 자산을 확인하세요.</span></div><button type="button" data-action="show-os-upgrades">대상 자산 보기</button></div>` : ""}`;
}

function osSupportBadge(support) {
  return support?.upgradeRequired ? `<div class="os-support-badge"><span class="badge bad" title="${esc(support.label + " · " + support.endedOn + " · " + support.note)}">OS 업그레이드 필요</span></div>` : "";
}
function osSupportBanner(support) {
  if (!support?.upgradeRequired) return "";
  return `<div class="note os-support-notice" role="status"><strong>OS 업그레이드 필요 · ${esc(support.label)}</strong><p>종료일 ${esc(support.endedOn)} · ${esc(support.note)}</p><div>${esc(support.action)}</div><div class="small"><a href="${esc(support.sourceUrl)}" target="_blank" rel="noopener noreferrer">공식 지원 정책</a> · 정책 확인 ${esc(support.policyVerifiedOn)} · 판정 기준 ${esc(support.evaluatedOn)} · CVE 발견 건수와 별도로 관리합니다.</div></div>`;
}

function assetTable(items) {
  if (!items.length) return empty("표시할 자산이 없습니다", "검색 조건을 바꾸거나 점검 대상에서 서버를 등록하세요.", '<a class="button" href="#targets">점검 대상 관리 →</a>');
  return table(["서버 / 자산번호", "환경 / 담당", "OS / 자원", "서비스 / 절차 보완", "최근 점검 / 발견·의심", "관리"], items.map(a => `<tr>
    <td><a href="${assetLink(a.id)}"><b>${esc(a.name)}</b></a><div class="small">${esc(a.host)}</div><div class="small">${esc(a.asset_code || "자산번호 미입력")}</div></td>
    <td>${badge(environments[a.environment] || "미지정")}<div>${filled(a.owner)}</div><div class="small">${esc(lifecycles[a.lifecycle] || "운영")}</div></td>
    <td>${a.inventory ? `${esc(a.inventory.distribution)}${osSupportBadge(a.os_support)}<div class="small">CPU ${a.inventory.host.processors || "미수집"} · ${memory(a.inventory.host.physicalMemoryBytes)}</div>` : '<span class="muted">점검 전 · 미수집</span>'}</td>
    <td>${number(a.service_count)}개<div class="small">절차 보완 ${number(a.undocumented_count)}개</div></td>
    <td>${a.latest_status ? badge(a.latest_status) : badge("점검 전")}<div class="small">최근 수집 ${date(a.observed_at)}</div>${a.job_id ? `<span>${number(a.finding_count)}건</span>` : ""}</td>
    <td><a class="button" href="${assetLink(a.id)}">자산 열기</a></td></tr>`));
}

async function assetView() {
  if (location.hash === "#assets/report" || location.hash.startsWith("#assets/report/")) return assetReportView();
  const target = location.hash.startsWith("#assets/") ? decodeURIComponent(location.hash.slice(8)) : "";
  const revision = renderRevision;
  if (target) {
    const data = await api("/assets/" + encodeURIComponent(target));
    if (revision === renderRevision) {
      if (assetDetail?.asset.id !== target) { serviceQuery = ""; serviceFilter = "active"; excludeSystemServices = false; }
      assetDetail = data;
      editingService = null;
    }
    return assetDetailHtml(data);
  }
  const query = assetQuery.toLocaleLowerCase(), environment = assetEnvironment, filter = assetFilter;
  const {items, summary} = await api("/assets");
  const selected = items.filter(a => (!query || [a.name, a.host, a.asset_code, a.owner, a.location, a.purpose, a.inventory?.distribution].join(" ").toLocaleLowerCase().includes(query))
    && (!environment || (a.environment || "UNSPECIFIED") === environment)
    && (!filter || (filter === "os-upgrade" && a.os_support?.upgradeRequired) || (filter === "unscanned" && !a.job_id) || (filter === "undocumented" && a.undocumented_count > 0) || (filter === "failed" && a.latest_status === "FAILED")));
  return pageHead("자산 관리", "서버별 자산 정보와 서비스 운영대장을 함께 관리하세요.", `${admin() ? '<a class="button primary" href="#offline">수집 결과 업로드</a>' : ''}<a class="button" href="#assets/report">전체 자산 MD 보고서</a><button data-asset-export="" title="검색 조건과 관계없이 저장된 전체 자산·서비스를 내려받습니다.">전체 자산 XLSX</button><a class="button" href="#targets">점검 대상 관리 →</a>`) + assetKpis(summary)
    + `<section class="panel"><form id="asset-search" class="asset-filters"><label>자산 검색<input name="query" maxlength="200" value="${esc(assetQuery)}" placeholder="서버·주소·자산번호·담당자"></label><label>환경<select name="environment"><option value="">전체 환경</option>${options(environments, environment)}</select></label><label>보완 상태<select name="filter">${options({"": "전체 자산", "os-upgrade": "OS 업그레이드 필요", unscanned: "점검 전", undocumented: "운영 절차 보완", failed: "최근 점검 실패"}, filter)}</select></label><button type="submit">검색</button></form><div class="panel-title"><h2>자산 목록</h2><span class="small">검색 ${number(selected.length)} / 전체 ${number(items.length)}대</span></div>${assetTable(selected)}</section>`;
}

function assetProfile(a) {
  const disabled = admin() ? "" : "disabled";
  const input = (label, name, value, max) => `<label>${label}<input name="${name}" maxlength="${max}" value="${esc(value)}" ${disabled}></label>`;
  return `<details class="panel asset-profile"><summary>자산 정보 ${a.owner ? "· " + esc(a.owner) : "· 담당자·환경·용도를 입력하세요"}</summary><form id="asset-profile" data-target="${esc(a.id)}" data-revision="${a.revision}"><div class="grid">
    ${input("자산번호", "assetCode", a.asset_code, 120)}<label>환경<select name="environment" ${disabled}>${options(environments, a.environment || "UNSPECIFIED")}</select></label>
    ${input("담당자 / 담당파트", "owner", a.owner, 120)}${input("서버 위치", "location", a.location, 200)}
    <label>운영 상태<select name="lifecycle" ${disabled}>${options(lifecycles, a.lifecycle || "ACTIVE")}</select></label>${input("서버 용도", "purpose", a.purpose, 1000)}</div>
    <label>자산 메모<textarea name="notes" maxlength="4000" ${disabled}>${esc(a.notes)}</textarea></label>
    <div class="form-actions"><span class="small">${a.updated_at ? esc(a.updated_by) + " · " + date(a.updated_at) : "아직 수기 정보를 저장하지 않았습니다."}</span>${admin() ? '<button type="button" data-action="cancel-profile">입력 취소</button><button type="submit" class="primary">자산 정보 저장</button>' : ""}</div></form></details>`;
}

function assetDetailHtml(data) {
  const a = data.asset, i = a.inventory;
  return pageHead(a.name, `${a.host} · ${a.asset_code || "자산번호 미입력"}`, `<a class="button" href="#assets">자산 목록</a><a class="button" href="#assets/report/${encodeURIComponent(a.id)}">자산 MD 보고서</a><button data-asset-export="${esc(a.id)}" title="이 자산의 저장된 전체 서비스(보관 포함)를 내려받습니다.">자산 XLSX</button><button data-action="refresh-asset">새로고침</button>${admin() ? `<a class="button" href="#offline/${encodeURIComponent(a.id)}">결과 업로드</a>` : ""}${a.collection_mode === "UPLOAD" ? `<a class="button" href="/api/collector/observer-collect.sh">수집 SH 다운로드</a>` : `<button class="primary" data-action="scan" data-id="${esc(a.id)}" ${["RUNNING", "QUEUED"].includes(a.latest_status) ? "disabled" : ""}>점검 시작</button>`}`, true)
    + (a.latest_status === "FAILED" ? `<div class="note warn">최근 점검 실패 · ${esc(a.latest_message)} 기존 수집 정보는 ${date(a.observed_at)} 기준입니다.</div>` : "")
    + `<div class="asset-summary panel"><div><span class="small">수집된 호스트 / OS</span><b>${esc(i?.host.name || "미수집")}</b><span>${esc(i?.distribution || "점검 후 표시됩니다")}</span></div><div><span class="small">CPU / 메모리</span><b>${i ? (i.host.processors || "미수집") + " CPU · " + memory(i.host.physicalMemoryBytes) : "미수집"}</b><span>${esc(i?.host.architecture || "")}</span></div><div><span class="small">수집 기준 시각</span><b>${date(a.observed_at)}</b><span>${a.latest_status ? badge(a.latest_status) : "점검 전"} ${a.job_id ? `· 발견·의심 ${number(a.finding_count)}건` : ""}</span></div></div>`
    + osSupportBanner(a.os_support) + assetProfile(a)
    + `<section class="panel"><div class="panel-title"><div><h2>서비스 운영대장</h2><p class="small">서비스·포트·실행계정·경로와 운영 절차를 한 행에서 관리합니다.</p></div>${admin() ? '<button class="primary" data-action="add-service">+ 수동 서비스</button>' : '<span class="badge">조회 권한</span>'}</div>
    <div class="note">자동 수집은 최근 점검의 관측값입니다. 시작·종료·상태 확인 방법은 기록용이며 실행되지 않습니다. 수기 정보는 재점검 후에도 유지됩니다.${i ? `<br>포트 수집: ${esc({COLLECTED:"수집 완료", NOT_COLLECTED:"미수집", FAILED:"조회 실패", UNAVAILABLE:"도구 없음", TIMEOUT:"시간 제한", TRUNCATED:"일부 수집"}[i.socketStatus] || i.socketStatus)} · ${esc(i.socketMessage)} · 수집 제한 ${number(i.issueCount)}건` : ""}</div>
    <form id="service-search" class="asset-filters"><label>서비스 검색<input name="query" maxlength="200" value="${esc(serviceQuery)}" placeholder="서비스·SW·담당·포트·경로"></label><label>표시 항목<select name="filter">${options({active:"관리 중", undocumented:"절차 보완", unobserved:"최근 수집 미확인", archived:"보관함", all:"전체"}, serviceFilter)}</select></label><button type="submit">검색</button></form><div id="service-grid">${serviceGrid(data.services)}</div></section>`
    + `<section class="panel"><div class="panel-title"><h2>이 자산의 최근 점검</h2><span class="small">최근 20회 · 당시 보고서 보존</span></div>${jobsTable(data.jobs)}</section>`;
}

// A display filter based on observed identity, never on a handwritten label, port number or root ownership.
const systemProcessNames = new Set([
  "init", "systemd", "systemd-resolved", "systemd-networkd", "systemd-timesyncd", "systemd-journald", "systemd-udevd", "systemd-logind",
  "systemd-resolve", "systemd-network", "systemd-timesyn", "systemd-journal", // Linux comm may be truncated.
  "sshd", "chronyd", "ntpd", "rpcbind", "rpc.statd", "rpc.mountd", "rpc.idmapd", "rpc.gssd", "rpc.nfsd",
  "avahi-daemon", "dhclient", "dhcpcd", "networkmanager", "cupsd", "cups-browsed",
]);
function isSystemService(service) {
  const observed = service.observed;
  if (service.source !== "AUTO" || observed?.kind !== "PROCESS") return false;
  const executable = (observed.executable || "").trim().replace(/ \(deleted\)$/, "");
  const name = (observed.name || "").trim().toLowerCase();
  if (!executable) {
    return systemProcessNames.has(name) || /^\[?(?:kernel|kthreadd|kworker(?:[/:][^\]]+)?|ksoftirqd\/\d+|migration\/\d+|watchdog\/\d+|rcu_(?:sched|bh|preempt)|kswapd\d*|kcompactd\d*|khugepaged|kthrotld|nfsd|lockd|rpciod|xprtiod)\]?$/.test(name);
  }
  // A business executable with a similar name under /APP or /opt remains visible.
  return /^(?:\/(?:usr\/)?(?:s?bin)\/|\/(?:usr\/)?lib(?:64)?\/systemd\/)[^/]+$/.test(executable)
    && systemProcessNames.has(executable.slice(executable.lastIndexOf("/") + 1).toLowerCase());
}
function serviceGrid(services) {
  const query = serviceQuery.toLocaleLowerCase();
  const matching = services.filter(s => (serviceFilter === "all" || (serviceFilter === "archived" ? s.archived : !s.archived))
    && (serviceFilter !== "undocumented" || !s.start_command || !s.stop_command || !s.status_command)
    && (serviceFilter !== "unobserved" || s.observation_status === "NOT_OBSERVED")
    && (!query || [s.name, s.owner, s.software, s.bindings, s.install_path, s.data_path, s.log_path, s.observed?.name, s.observed?.bindings.join(" ")].join(" ").toLocaleLowerCase().includes(query)));
  const items = excludeSystemServices ? matching.filter(s => !isSystemService(s)) : matching;
  const hidden = matching.length - items.length;
  const columns = [
    ["service", "서비스 / 분류"], ["software", "SW / 버전", true], ["owner", "담당 / 실행계정", true],
    ["paths", "APP · DATA · LOG", true], ["ports", "바인딩 포트"], ["start", "시작 방법"],
    ["stop", "종료 방법"], ["status", "상태 확인 방법"], ["notes", "연계 / 메모", true],
    ["pids", "PID"], ["observation", "관측"], ...(admin() ? [["actions", "편집"]] : []),
  ];
  const visibleColumns = columns.filter(c => serviceColumns !== "operations" || !c[2]).length;
  return `<div class="panel-title"><p class="small" role="status">표시 ${number(items.length)} / 전체 ${number(services.length)}개${excludeSystemServices ? ` · 시스템 서비스 ${number(hidden)}개 제외` : ""} · 넓은 표는 좌우로 스크롤하세요.</p><div class="actions" aria-label="컬럼과 서비스 표시"><button type="button" data-action="service-columns" data-mode="operations" aria-pressed="${serviceColumns === "operations"}">운영 절차</button><button type="button" data-action="service-columns" data-mode="all" aria-pressed="${serviceColumns === "all"}">전체 항목</button><button type="button" data-action="toggle-system-services" aria-pressed="${excludeSystemServices}" aria-controls="service-ledger" title="커널·systemd·SSH·시간 동기화 등 확인 가능한 OS 기본 서비스를 숨깁니다. 다시 누르면 표시합니다.">시스템 서비스 항목 제외</button></div></div><div id="service-ledger" class="table-wrap service-grid ${serviceColumns === "operations" ? "operations" : ""}" tabindex="0" aria-label="서비스 운영대장 가로 스크롤"><table><thead><tr>${columns.map(([key, label, secondary]) => `<th scope="col" class="col-${key}${secondary ? " secondary-column" : ""}">${label}</th>`).join("")}</tr></thead><tbody>${items.length ? items.map(serviceRow).join("") : `<tr><td class="ledger-empty" colspan="${visibleColumns}">${hidden ? "시스템 서비스 제외를 해제하면 숨긴 항목을 볼 수 있습니다." : "표시할 서비스가 없습니다. 검색 조건을 바꾸거나 수동 서비스를 추가하세요."}</td></tr>`}</tbody></table></div>`;
}

// Display-only grouping. Original observations and service identities remain intact.
function portGroups(bindings = []) {
  const groups = new Map();
  const add = (label, value) => {
    if (!groups.has(label)) groups.set(label, new Set());
    groups.get(label).add(value);
  };
  for (const raw of bindings) {
    const socket = /^(TCP|UDP|SCTP)\s+(.+):(\d+|\*)$/i.exec(raw);
    if (socket) { add(`${socket[1].toUpperCase()} ${socket[3]}`, raw); continue; }
    const mapped = /^Docker (.+):(\d+) -> (\d+)\/(tcp|udp|sctp)$/i.exec(raw);
    if (mapped) { add(`Docker ${mapped[4].toUpperCase()} ${mapped[2]} → ${mapped[3]}`, raw); continue; }
    const docker = /^Docker (\d+)\/(tcp|udp|sctp)(?:\s+(.*))?$/i.exec(raw);
    if (docker) {
      const [, port, protocol, tail = ""] = docker;
      const label = `Docker ${protocol.toUpperCase()}`;
      if (tail.startsWith("[")) {
        try {
          const entries = JSON.parse(tail);
          if (Array.isArray(entries) && entries.length && entries.every(e => typeof e?.HostIp === "string" && /^\d+$/.test(e.HostPort))) {
            entries.forEach(e => add(`${label} ${e.HostPort} → ${port}`, `호스트 ${e.HostIp.includes(":") ? `[${e.HostIp}]` : e.HostIp}:${e.HostPort} → 컨테이너 ${port}/${protocol.toLowerCase()}`));
            continue;
          }
          if (Array.isArray(entries) && !entries.length) { add(`${label} ${port} · 내부`, raw); continue; }
        } catch (_) { /* Unknown formats remain available verbatim in the detail. */ }
      }
      if (!tail || tail === "null" || tail === "(컨테이너 내부)") {
        add(`${label} ${port} · ${tail ? "내부" : "선언"}`, raw); continue;
      }
    }
    add(raw.startsWith("Docker ") ? "Docker · 기타" : "기타 포트", raw);
  }
  return [...groups].map(([label, values]) => ({label, values: [...values]}))
    .sort((a, b) => a.label.localeCompare(b.label, "en", {numeric: true}));
}

function observedPorts(s) {
  const ports = portGroups(s.observed?.bindings);
  // Keep only actual values in the cell; provenance and missing fields live in the shared detail popup.
  return `<button type="button" class="port-preview" data-asset-detail="ports" data-service-id="${esc(s.id)}" aria-label="바인딩·운영 포트 상세 보기" aria-expanded="false" aria-controls="asset-popover">${ports.length
    ? `<span class="port-chips">${ports.slice(0, 4).map(p => `<span class="port-chip">${esc(p.label)}</span>`).join("")}${ports.length > 4 ? `<span class="port-more">+${ports.length - 4}</span>` : ""}</span>` : ""}
    ${s.bindings ? `<span class="managed-ports">${esc(s.bindings)}</span>` : ""}${!ports.length && !s.bindings ? '<span class="muted">—</span>' : ""}</button>`;
}
function portDetail(s) {
  const ports = portGroups(s.observed?.bindings);
  return `<div class="port-detail"><b>자동 관측${s.observation_status === "NOT_OBSERVED" ? " · 이전 수집" : ""}</b>${ports.length
    ? ports.map(p => `<div class="port-binding"><span>${esc(p.label)}</span><div class="cell-lines">${p.values.map(esc).join("\n")}</div></div>`).join("")
    : `<p class="small">${s.source === "MANUAL" ? "수동 등록 항목 · 자동 관측 없음" : "관측 포트 미확인 · 포트가 없다는 뜻은 아닙니다."}</p>`}</div>
    <div class="port-detail"><b>운영 포트 / 보완</b><div class="cell-lines">${s.bindings ? esc(s.bindings) : "운영 포트 미입력"}</div></div>`;
}
function pidChips(pids) {
  return pids.map(pid => `<span class="pid-chip">${esc(pid)}</span>`).join(" ");
}
function observedPids(s) {
  const pids = s.observed?.pids || [];
  if (!pids.length) return "";
  const preview = [];
  let digits = 0;
  for (const pid of pids.slice(0, 3)) {
    const remaining = pids.length - preview.length - 1;
    // Reserve space for badge padding and the remaining-count label in the fixed PID column.
    const width = digits + String(pid).length + (preview.length + 1) * 2 + (remaining ? String(remaining).length + 1 : 0);
    if (preview.length && width > 24) break;
    preview.push(pid); digits += String(pid).length;
  }
  return `<button type="button" class="pid-preview" data-asset-detail="pids" data-service-id="${esc(s.id)}" aria-label="PID 전체 ${pids.length}개 보기" aria-expanded="false" aria-controls="asset-popover">${pidChips(preview)}${pids.length > preview.length ? ` <span class="pid-more">+${pids.length - preview.length}</span>` : ""}</button>`;
}
function observedStatus(s) {
  const status = s.archived ? "보관" : {OBSERVED:"확인", NOT_OBSERVED:"미확인", MANUAL:"수동"}[s.observation_status] || "미확인";
  return `<button type="button" class="observation-preview" data-asset-detail="observation" data-service-id="${esc(s.id)}" aria-label="관측 상태와 수정 이력 상세 보기" aria-expanded="false" aria-controls="asset-popover">${badge(status)}${s.last_seen_at ? `<span class="observation-time">${assetTime(s.last_seen_at)}</span>` : ""}</button>`;
}
function observationDetail(s) {
  const status = {OBSERVED:"최근 수집 확인", NOT_OBSERVED:"최근 수집 미확인", MANUAL:"수동 등록"}[s.observation_status] || "미확인";
  return `<div class="cell-lines">${esc(status)}${s.archived ? " · 보관" : ""}</div>${s.observation_status === "NOT_OBSERVED" ? '<p class="small">이전 관측값입니다. 중지·삭제로 판정하지 않습니다.</p>' : ""}
    <div class="port-detail"><b>최근 관측</b><div>${s.last_seen_at ? date(s.last_seen_at) : "관측 이력 없음"}</div></div>
    <div class="port-detail"><b>수기 정보 수정</b><div>${s.updated_at ? esc(s.updated_by) + " · " + date(s.updated_at) : "수정 이력 없음"}</div></div>`;
}
function serviceRow(s) {
  const o = s.observed;
  return `<tr data-service="${esc(s.id)}"><td class="col-service"><b>${esc(s.name || o?.name || "이름 미입력")}</b><div class="small">${esc(serviceTypes[s.service_type])}</div><div class="small">${o ? esc(o.category) + " · " + esc({CONTAINER:"컨테이너", PROCESS:"프로세스", SOCKET:"소켓"}[o.kind]) : "수동 등록"}</div></td>
    <td class="col-software secondary-column">${filled(s.software)}<div class="small">버전 ${filled(s.software_version)}</div>${o?.image ? `<div class="small">관측 이미지 ${esc(o.image)}</div>` : ""}</td>
    <td class="col-owner secondary-column">${filled(s.owner)}<div class="small">실행 ${filled(s.run_user || o?.runUser)}</div></td>
    <td class="col-paths secondary-column"><div class="ledger-fields"><span class="small">APP</span><span class="cell-lines">${filled(s.install_path)}</span><span class="small">DATA</span><span class="cell-lines">${filled(s.data_path)}</span><span class="small">LOG</span><span class="cell-lines">${filled(s.log_path)}</span></div>${o?.executable ? `<details><summary>관측 실행 파일</summary>${esc(o.executable)}</details>` : ""}</td>
    <td class="col-ports">${observedPorts(s)}</td>
    <td class="col-start cell-lines">${filled(s.start_command)}</td><td class="col-stop cell-lines">${filled(s.stop_command)}</td><td class="col-status cell-lines">${filled(s.status_command)}</td>
    <td class="col-notes secondary-column"><div class="ledger-fields"><span class="small">연계</span><span class="cell-lines">${filled(s.dependencies)}</span><span class="small">메모</span><span class="cell-lines">${filled(s.notes)}</span></div></td>
    <td class="col-pids">${observedPids(s) || '<span class="muted" title="관측 PID 없음 또는 미확인">—</span>'}</td>
    <td class="col-observation">${observedStatus(s)}</td>
    ${admin() ? `<td class="col-actions"><button data-action="edit-service" data-id="${esc(s.id)}" aria-label="${esc(s.name || o?.name || "서비스")} 편집">편집</button></td>` : ""}</tr>`;
}

function editService(id) {
  if ($("#asset-profile")?.dataset.dirty === "true") { toast("자산 정보 입력을 먼저 저장하거나 취소하세요."); return; }
  if (!assetDetail || editingService) { toast("편집 중인 서비스를 먼저 저장하거나 취소하세요."); return; }
  const s = id ? assetDetail.services.find(row => row.id === id) : {id:"new", revision:0, service_type:"OTHER"};
  if (!s) return;
  editingService = s.id;
  setAssetBusy(true);
  const field = (label, name, value, max, placeholder = "", rows = 2) => `<label>${label}<textarea name="${name}" maxlength="${max}" rows="${rows}" placeholder="${esc(placeholder)}">${esc(value)}</textarea></label>`;
  const text = (label, name, value, max, placeholder = "") => `<label>${label}<input name="${name}" maxlength="${max}" value="${esc(value)}" placeholder="${esc(placeholder)}" ${name === "name" && !s.observed ? "required" : ""}></label>`;
  $("#service-editor").innerHTML = `<form id="service-edit" class="service-edit" data-id="${esc(id || "")}" data-revision="${s.revision}">
    <div class="editor-header"><div><h2 id="service-editor-title">${id ? "서비스 운영 정보 편집" : "수동 서비스 등록"}</h2><p>${esc(assetDetail.asset.name)} · ${esc(s.name || s.observed?.name || "새 서비스")}</p></div><button type="button" data-action="cancel-service" aria-label="편집 창 닫기">닫기</button></div>
    <div class="editor-body"><p class="note">자동 관측값은 보존됩니다. 시작·종료·상태 확인 방법은 기록용이며 실행하지 않습니다.</p>
    <fieldset><legend>기본 정보</legend><div class="editor-grid">
    ${text("서비스명", "name", s.name, 160, s.observed?.name || "서비스명 입력")}<label>서비스 구분<select name="serviceType">${options(serviceTypes, s.service_type)}</select></label>
    ${text("SW", "software", s.software, 200)}${text("SW 버전", "softwareVersion", s.software_version, 200)}
    ${text("담당파트 / 담당자", "owner", s.owner, 120)}${text("실행계정", "runUser", s.run_user, 120, s.observed?.runUser || "실행계정")}</div></fieldset>
    <fieldset><legend>경로와 포트</legend><div class="editor-grid">
    ${field("APP / 설치위치", "installPath", s.install_path, 2000)}${field("DATA 경로", "dataPath", s.data_path, 2000)}
    ${field("LOG 경로", "logPath", s.log_path, 2000)}${field("운영 포트 / 보완", "bindings", s.bindings, 2000, "예: TCP 8080, 관리 8443")}</div>
    ${s.observed ? `<details><summary>자동 수집 참고 · 바인딩 포트 / 실행 파일</summary><pre>${esc((s.observed.bindings || []).join("\n") || "포트 미확인")}\n${esc(s.observed.executable || "실행 파일 미확인")}</pre></details>` : ""}</fieldset>
    <fieldset><legend>실행 절차</legend><div class="command-fields">
    ${field("시작 방법", "startCommand", s.start_command, 4000, "실행 위치, 명령, 필요한 절차를 줄바꿈하여 기록하세요.", 5)}
    ${field("종료 방법", "stopCommand", s.stop_command, 4000, "종료 순서와 확인 방법", 5)}
    ${field("상태 확인 방법", "statusCommand", s.status_command, 4000, "상태 확인 명령 또는 점검 절차", 5)}</div></fieldset>
    <fieldset><legend>연계와 메모</legend><div class="editor-grid">${field("연계 대상 / 통신 방향", "dependencies", s.dependencies, 4000, "예: 내부 → 외부, 목적지:포트, 용도", 4)}${field("비고", "notes", s.notes, 4000, "운영 시 참고 사항", 4)}</div>
    <label class="check"><input type="checkbox" name="archived" ${s.archived ? "checked" : ""}>보관 처리</label></fieldset></div>
    <div class="editor-actions"><p class="error" role="alert"></p><span class="small">빈 수기 값은 미입력으로 저장합니다.</span><button type="button" data-action="cancel-service">취소</button><button type="submit" class="primary">서비스 저장</button></div></form>`;
  $("#service-editor").showModal();
  $("#service-edit input").focus();
}
function cancelServiceEdit() {
  const form = $("#service-edit");
  if (form?.dataset.saving === "true") return;
  if (form?.dataset.dirty === "true" && !window.confirm("입력한 내용을 버리고 편집 창을 닫을까요?")) return;
  editingService = null;
  $("#service-editor").close();
  setAssetBusy(false);
}
document.addEventListener("cancel", event => {
  if (event.target.id === "service-editor") { event.preventDefault(); cancelServiceEdit(); }
}, true);

function setAssetBusy(busy) {
  closeAssetPopover();
  document.querySelectorAll('#service-search input, #service-search select, #service-search button, [data-asset-export], [data-action="refresh-asset"], [data-action="add-service"], [data-action="edit-service"], [data-action="service-columns"], [data-action="toggle-system-services"], #asset-profile input, #asset-profile select, #asset-profile textarea, #asset-profile button, [data-action="scan"]').forEach(el => el.disabled = busy);
}

document.addEventListener("click", async event => {
  if (event.target.closest('[data-action="show-os-upgrades"]')) {
    if (hasAssetDraft()) return;
    assetQuery = ""; assetEnvironment = ""; assetFilter = "os-upgrade";
    if (location.hash === "#assets") await render(); else location.hash = "#assets";
    return;
  }
  const button = event.target.closest("[data-action]");
  if (!button || button.disabled) return;
  if (button.dataset.action === "edit-service") editService(button.dataset.id);
  if (button.dataset.action === "add-service") editService();
  if (button.dataset.action === "cancel-service") {
    cancelServiceEdit();
  }
  if (button.dataset.action === "refresh-asset") await render();
  if (button.dataset.action === "cancel-profile") { $("#asset-profile").dataset.dirty = "false"; await render(); }
  if (button.dataset.action === "service-columns") {
    closeAssetPopover();
    serviceColumns = button.dataset.mode; $("#service-grid").innerHTML = serviceGrid(assetDetail.services);
  }
  if (button.dataset.action === "toggle-system-services") {
    closeAssetPopover();
    excludeSystemServices = !excludeSystemServices;
    $("#service-grid").innerHTML = serviceGrid(assetDetail.services);
    $("[data-action='toggle-system-services']").focus({preventScroll: true});
  }
});

document.addEventListener("submit", async event => {
  const form = event.target;
  if (!["asset-search", "asset-profile", "service-search"].includes(form.id) && !form.matches(".service-edit")) return;
  event.preventDefault();
  const data = Object.fromEntries(new FormData(form));
  const button = form.querySelector('[type="submit"]');
  button.disabled = true;
  if (form.matches(".service-edit")) { form.dataset.saving = "true"; form.querySelectorAll("button").forEach(b => b.disabled = true); }
  try {
    if (form.id === "asset-search") {
      assetQuery = data.query; assetEnvironment = data.environment; assetFilter = data.filter; await render();
    } else if (form.id === "service-search") {
      serviceQuery = data.query; serviceFilter = data.filter; $("#service-grid").innerHTML = serviceGrid(assetDetail.services);
    } else if (form.id === "asset-profile") {
      data.revision = Number(form.dataset.revision);
      await api("/admin/assets/" + encodeURIComponent(form.dataset.target), {method:"PUT", body:data});
      form.dataset.dirty = "false"; toast("자산 정보를 저장했습니다."); await render();
    } else {
      data.revision = Number(form.dataset.revision); data.archived = data.archived === "on";
      const path = "/admin/assets/" + encodeURIComponent(assetDetail.asset.id) + "/services" + (form.dataset.id ? "/" + encodeURIComponent(form.dataset.id) : "");
      await api(path, {method:form.dataset.id ? "PUT" : "POST", body:data});
      editingService = null; $("#service-editor").close(); toast("서비스 운영 정보를 저장했습니다."); await render();
    }
  } catch (error) {
    if (form.querySelector(".error")) form.querySelector(".error").textContent = error.message;
    toast(error.message);
  } finally { button.disabled = false; if (form.matches(".service-edit")) { form.dataset.saving = "false"; form.querySelectorAll("button").forEach(b => b.disabled = false); } }
});
document.addEventListener("input", event => {
  const service = event.target.closest("#service-edit");
  if (service) service.dataset.dirty = "true";
  const form = event.target.closest("#asset-profile");
  if (form) {
    form.dataset.dirty = "true";
    document.querySelectorAll('[data-asset-export], [data-action="refresh-asset"], [data-action="scan"], [data-action="add-service"], [data-action="edit-service"]').forEach(button => button.disabled = true);
  }
});
function hasAssetDraft() { return !!editingService || $("#asset-profile")?.dataset.dirty === "true"; }
window.addEventListener("beforeunload", event => {
  if (hasAssetDraft()) { event.preventDefault(); event.returnValue = ""; }
});

let assetPopoverTrigger = null, assetPopoverTimer, assetPopoverPinned = false;
function closeAssetPopover() {
  clearTimeout(assetPopoverTimer);
  assetPopoverTrigger?.setAttribute("aria-expanded", "false");
  assetPopoverTrigger?.removeAttribute("aria-describedby");
  assetPopoverTrigger = null;
  assetPopoverPinned = false;
  const popup = $("#asset-popover");
  if (popup) popup.hidden = true;
}
function openAssetPopover(trigger, pinned = false) {
  const s = assetDetail?.services.find(row => row.id === trigger.dataset.serviceId);
  if (!s) return;
  closeAssetPopover();
  const popup = $("#asset-popover"), kind = trigger.dataset.assetDetail;
  const pids = s.observed?.pids || [];
  const heading = kind === "pids" ? `PID · 전체 ${pids.length}개` : kind === "ports" ? "바인딩·운영 포트" : "관측 정보";
  popup.innerHTML = `<strong>${heading}</strong><p class="small">${esc(s.name || s.observed?.name || "서비스")}${s.last_seen_at && kind !== "observation" ? " · " + date(s.last_seen_at) : ""}</p>`
    + (kind === "pids" ? `<p class="pid-values">${pidChips(pids)}</p>` : kind === "ports" ? portDetail(s) : observationDetail(s));
  assetPopoverTrigger = trigger; trigger.setAttribute("aria-expanded", "true"); trigger.setAttribute("aria-describedby", "asset-popover");
  assetPopoverPinned = pinned;
  popup.hidden = false;
  positionAssetPopover();
}
function positionAssetPopover() {
  if (!assetPopoverTrigger) return;
  if (!assetPopoverTrigger.isConnected) { closeAssetPopover(); return; }
  const popup = $("#asset-popover"), rect = assetPopoverTrigger.getBoundingClientRect();
  if (rect.bottom < 0 || rect.top > window.innerHeight || rect.right < 0 || rect.left > window.innerWidth) { closeAssetPopover(); return; }
  // Keep the popup away from its trigger: covering it during focus can cancel a touch click.
  popup.style.maxHeight = "";
  const below = Math.max(0, window.innerHeight - rect.bottom - 14), above = Math.max(0, rect.top - 14);
  const placeBelow = below >= popup.offsetHeight || below >= above;
  popup.style.maxHeight = Math.min(popup.offsetHeight, placeBelow ? below : above) + "px";
  popup.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - popup.offsetWidth - 8)) + "px";
  popup.style.top = Math.max(8, placeBelow ? rect.bottom + 6 : rect.top - popup.offsetHeight - 6) + "px";
}
document.addEventListener("pointerover", event => {
  if (event.pointerType === "touch" || assetPopoverPinned) return;
  const trigger = event.target.closest("[data-asset-detail]");
  if (trigger && !trigger.contains(event.relatedTarget)) openAssetPopover(trigger);
  if (event.target.closest("#asset-popover")) clearTimeout(assetPopoverTimer);
});
document.addEventListener("pointerout", event => {
  if (event.pointerType === "touch" || assetPopoverPinned) return;
  const area = event.target.closest("[data-asset-detail], #asset-popover");
  if (area && !area.contains(event.relatedTarget)) assetPopoverTimer = setTimeout(closeAssetPopover, 180);
});
document.addEventListener("focusin", event => {
  const trigger = event.target.closest("[data-asset-detail]");
  if (trigger) openAssetPopover(trigger);
  else if (!event.target.closest("#asset-popover")) closeAssetPopover();
});
document.addEventListener("keydown", event => {
  if (event.key === "Escape") {
    if ($("#asset-popover")?.contains(document.activeElement)) assetPopoverTrigger?.focus({preventScroll:true});
    closeAssetPopover();
  }
});
document.addEventListener("scroll", event => { if (!event.target.closest?.("#asset-popover")) positionAssetPopover(); }, true);
window.addEventListener("resize", positionAssetPopover);
document.addEventListener("click", async event => {
  const trigger = event.target.closest("[data-asset-detail]");
  if (trigger) { openAssetPopover(trigger, true); return; }
  if (!event.target.closest("#asset-popover")) closeAssetPopover();
  const button = event.target.closest("[data-asset-export]");
  if (!button || button.disabled) return;
  if (hasAssetDraft()) { toast("입력 내용을 저장하거나 취소한 뒤 내려받으세요."); return; }
  button.disabled = true;
  const label = button.textContent;
  button.textContent = "XLSX 생성 중…";
  try {
    const target = button.dataset.assetExport;
    const response = await fetch("/api/assets" + (target ? "/" + encodeURIComponent(target) : "") + "/export.xlsx");
    if (!response.ok) throw new Error(response.status === 401 ? "다시 로그인한 뒤 내려받으세요." : "XLSX를 내려받지 못했습니다. 다시 시도하세요.");
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url; link.download = `observer-assets-${new Date().toISOString().slice(0, 10)}.xlsx`;
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30_000);
    toast("저장된 자산·서비스 전체 항목을 내려받았습니다. 보관 항목도 포함됩니다.");
  } catch (error) { toast(error.message); }
  finally { button.disabled = false; button.textContent = label; }
});
