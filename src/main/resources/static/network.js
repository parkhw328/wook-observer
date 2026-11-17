"use strict";
const networkViews = new Map();
const networkStatuses = {COLLECTED:"수집 완료", UNAVAILABLE:"도구·파일 없음", FAILED:"조회 실패", TIMEOUT:"시간 제한", TRUNCATED:"일부 수집", NOT_COLLECTED:"미수집"};
const networkStates = {ALL:"전체", LISTEN:"TCP LISTEN", ESTABLISHED:"TCP ESTABLISHED", OTHER_TCP:"기타 TCP 상태", UDP:"UDP"};
function networkEndpoint(address, port) {
  return address ? `${address.includes(":") ? `[${address}]` : address}:${port ?? "*"}` : "미수집";
}
function filterNetworkEntries(entries, filter) {
  const query = (filter.query || "").toLocaleLowerCase();
  return entries.filter(e => {
    const s = e.socket, state = s.state === "ESTAB" ? "ESTABLISHED" : s.state;
    return (filter.state === "ALL" || (filter.state === "UDP" ? s.protocol === "UDP" : s.protocol === "TCP" && (filter.state === "OTHER_TCP" ? !["LISTEN","ESTABLISHED"].includes(state) : state === filter.state)))
      && (!filter.system || !e.systemService) && (!filter.reserved || e.reservedPort !== true) && (!filter.ephemeral || e.ephemeralRange !== true)
      && (!query || [s.protocol,state,s.localAddress,s.localPort,s.peerAddress,s.peerPort,...(e.owners || []).flatMap(p => [p.pid,p.name,p.executable,p.containerId])].join(" ").toLocaleLowerCase().includes(query));
  });
}
function networkFlags(e) {
  const values = [];
  if (e.systemService) values.push("시스템 서비스 후보");
  if (e.reservedPort === true) values.push("로컬 예약 포트");
  if (e.ephemeralRange === true) values.push("자동 할당 범위 내");
  if (e.reservedPort == null || e.ephemeralRange == null) values.push("포트 정책 일부 미확인");
  return values.join(" · ") || "일반 / 미분류";
}
function networkPanel(data, key) {
  if (!data) return '<p class="small">네트워크 상세 미수집 · 새 SH로 다시 수집하세요.</p>';
  networkViews.set(key, {data, filter:{state:"ALL",query:"",system:false,reserved:false,ephemeral:false}, page:0});
  const established = data.entries.filter(e => e.socket.protocol === "TCP" && ["ESTABLISHED","ESTAB"].includes(e.socket.state)).length;
  return `<details class="panel network-panel" data-network="${esc(key)}"><summary>네트워크 관측 · ${number(data.entries.length)}건 <span>TCP ESTABLISHED ${number(established)}건 · ${esc(networkStatuses[data.connections.status] || "미수집")}</span></summary><div class="network-body">${networkBody(key)}</div></details>`;
}
function networkBody(key) {
  const view = networkViews.get(key), data = view.data, f = view.filter;
  const rows = filterNetworkEntries(data.entries, f), pages = Math.max(1, Math.ceil(rows.length / 50));
  view.page = Math.min(view.page, pages - 1);
  const setting = name => {
    const s = (data.settings || []).find(s => s.key === name);
    return !s || s.status !== "COLLECTED" ? esc(networkStatuses[s?.status] || "미수집") : esc(s.value || "설정 없음");
  };
  const toggles = [["system","시스템 서비스 제외"],["reserved","예약 포트 제외"],["ephemeral","자동 할당 범위 제외"]].map(([name,label]) => `<button type="button" data-network-toggle="${name}" aria-pressed="${f[name]}">${label}</button>`).join("");
  return `<p class="small">수집 시각 ${date(data.collectedAt)} · 바인딩 ${esc(networkStatuses[data.bindings.status])} / TCP 연결 ${esc(networkStatuses[data.connections.status])}. 수집 시점의 연결이며 실시간 상태·접속 방향·외부 접근 가능성을 뜻하지 않습니다.</p>
    <details class="network-evidence"><summary>수집 범위·포트 분류 근거</summary><p>${esc(data.bindings.message)}<br>${esc(data.connections.message)}</p><p>로컬 예약 포트: ${setting("ip_local_reserved_ports")}<br>자동 할당 범위: ${setting("ip_local_port_range")}</p><p>예약 포트는 커널의 자동 할당 제외 설정입니다. 자동 할당 범위 안에도 명시적으로 바인딩한 서비스가 있을 수 있습니다. 시스템 서비스 후보는 관측 프로세스 기준이며 소유자 미확인 항목은 숨기지 않습니다.</p></details>
    <div class="network-filter"><label>상태<select name="state">${Object.entries(networkStates).map(([value,label]) => `<option value="${value}" ${value===f.state?"selected":""}>${label}</option>`).join("")}</select></label><label>주소·포트·프로세스·PID<input name="query" maxlength="200" value="${esc(f.query)}" placeholder="로컬 / 상대 주소 또는 프로세스"></label><button type="button" data-network-search>검색</button></div>
    <div class="network-toolbar"><div class="actions">${toggles}</div><span class="small">표시 ${number(rows.length)} / 전체 ${number(data.entries.length)}건 · 내보내기는 전체</span></div>
    <div class="network-table"><table><thead><tr>${["프로토콜 / 상태","로컬 주소:포트","상대 주소:포트","프로세스","PID","분류 근거"].map(h=>`<th>${h}</th>`).join("")}</tr></thead><tbody>${rows.slice(view.page*50,view.page*50+50).map(e=>{
      const s=e.socket, owners=e.owners || [], names=owners.map(p=>p.name), ids=owners.map(p=>String(p.pid));
      return `<tr><td>${esc(s.protocol)} <b>${esc(s.state)}</b><div class="small">${esc(s.source)}</div></td><td>${esc(networkEndpoint(s.localAddress,s.localPort))}</td><td>${esc(networkEndpoint(s.peerAddress,s.peerPort))}</td><td title="${esc(names.join(" / "))}">${esc(names.slice(0,2).join(" / ") || "미확인")}${names.length>2?` +${names.length-2}`:""}</td><td tabindex="0" title="${esc(ids.join(" / ") || "권한·종료 등으로 소유 프로세스 미확인")}">${ids.slice(0,3).map(id=>`<span class="pid-chip">${esc(id)}</span>`).join(" ") || "—"}${ids.length>3?` +${ids.length-3}`:""}</td><td>${esc(networkFlags(e))}</td></tr>`;
    }).join("") || '<tr><td colspan="6">조건에 맞는 관측 항목이 없습니다. 수집 상태와 필터를 확인하세요.</td></tr>'}</tbody></table></div>
    <div class="network-pagination"><button type="button" data-network-page="-1" ${view.page===0?"disabled":""}>이전</button><span>${view.page+1} / ${pages} 페이지 · 페이지당 50건</span><button type="button" data-network-page="1" ${view.page>=pages-1?"disabled":""}>다음</button></div>`;
}
function updateNetwork(panel) { panel.querySelector(".network-body").innerHTML = networkBody(panel.dataset.network); }
function searchNetwork(panel) {
  const view=networkViews.get(panel.dataset.network), filter=panel.querySelector(".network-filter");
  view.filter.state=filter.querySelector('[name="state"]').value; view.filter.query=filter.querySelector('[name="query"]').value; view.page=0; updateNetwork(panel);
}
document.addEventListener("change", event => {
  if (event.target.matches('.network-filter [name="state"]')) searchNetwork(event.target.closest('[data-network]'));
});
document.addEventListener("keydown", event => {
  if (event.key === "Enter" && event.target.matches('.network-filter input')) { event.preventDefault(); searchNetwork(event.target.closest('[data-network]')); }
});
document.addEventListener("click", event => {
  const button=event.target.closest("[data-network-toggle],[data-network-page],[data-network-search]"); if (!button || button.disabled) return;
  const panel=button.closest("[data-network]"), view=networkViews.get(panel.dataset.network);
  if (button.hasAttribute("data-network-search")) { searchNetwork(panel); return; }
  if (button.dataset.networkToggle) { const key=button.dataset.networkToggle; view.filter[key]=!view.filter[key]; view.page=0; }
  else view.page+=Number(button.dataset.networkPage);
  const toggle=button.dataset.networkToggle; updateNetwork(panel);
  if (toggle) panel.querySelector(`[data-network-toggle="${toggle}"]`).focus();
});
