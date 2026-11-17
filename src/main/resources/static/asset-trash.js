"use strict";
let trashItems = null, trashQuery = "", pendingTrash = null;

const trashLink = () => admin() ? '<a class="button" href="#assets/trash">자산 휴지통</a>' : "";
function assetDeleteButton(asset) {
  return admin() ? `<button class="danger quiet" data-action="delete-asset" data-id="${esc(asset.id)}" data-name="${esc(asset.name)}" data-revision="${asset.trash_revision}" ${["RUNNING", "QUEUED"].includes(asset.latest_status) ? 'disabled title="점검 완료 후 삭제할 수 있습니다."' : ""}>자산 삭제</button>` : "";
}

async function assetTrashView() {
  if (!admin()) return empty("관리자 전용 기능입니다", "삭제한 자산의 복원은 관리자에게 요청하세요.", '<a class="button" href="#assets">자산 목록</a>');
  const revision = renderRevision;
  const data = await api("/admin/assets/trash");
  if (revision === renderRevision) trashItems = data;
  const matches = row => Object.values(row).join(" ").toLocaleLowerCase().includes(trashQuery.toLocaleLowerCase());
  const assets = data.assets.filter(matches), services = data.services.filter(matches);
  return pageHead("자산 휴지통", "삭제한 자산과 서비스를 확인하고 복원하세요.", '<a class="button" href="#assets">자산 목록</a><button data-action="refresh-trash">새로고침</button>', true)
    + '<div class="note">휴지통 항목은 자산 목록·점검 대상·자산 MD/XLSX에서 제외합니다. 등록 정보와 기존 점검 보고서는 보존하며 자동으로 영구 삭제하지 않습니다. 자산 복원 시 별도로 삭제했던 서비스는 계속 휴지통에 남습니다.</div>'
    + `<form id="trash-search" class="asset-filters"><label>휴지통 검색<input name="query" maxlength="200" value="${esc(trashQuery)}" placeholder="자산·서비스·주소·삭제한 사용자"></label><button type="submit">검색</button></form>
    <section class="panel"><div class="panel-title"><h2>삭제한 자산</h2><span class="small">${number(assets.length)} / ${number(data.assets.length)}대</span></div>${assets.length ? table(["자산 / 주소", "보존 자료", "삭제한 사용자 / 시각", "복원"], assets.map(a => `<tr><td><b>${esc(a.name)}</b><div class="small">${esc(a.host)} · ${esc(a.asset_code || "자산번호 미입력")}</div></td><td>서비스 ${number(a.service_count)}개 · 점검 ${number(a.job_count)}회<div class="small">자산 정보·계약·접속 설정 보존${a.deleted_service_count ? ` · 서비스 휴지통 ${number(a.deleted_service_count)}개 포함` : ""}</div></td><td>${esc(a.deleted_by)}<div class="small">${date(a.deleted_at)}</div></td><td><button data-action="restore-asset" data-id="${esc(a.id)}" aria-label="${esc(a.name)} 복원">복원</button></td></tr>`)) : '<p class="muted">표시할 삭제 자산이 없습니다.</p>'}</section>
    <section class="panel"><div class="panel-title"><h2>삭제한 서비스</h2><span class="small">${number(services.length)} / ${number(data.services.length)}개</span></div><p class="small">자산도 삭제했다면 먼저 위에서 자산을 복원하세요. 자동 수집 서비스도 직접 복원하기 전까지 대장에 다시 표시되지 않습니다.</p>${services.length ? table(["서비스", "소속 자산", "삭제한 사용자 / 시각", "복원"], services.map(s => `<tr><td><b>${esc(s.name)}</b><div class="small">${s.source === "AUTO" ? "자동 수집" : "수동 등록"}${s.archived ? " · 복원 후 숨긴 서비스에 표시" : ""}</div></td><td><a href="${assetLink(s.target_id)}">${esc(s.target_name)}</a><div class="small">${esc(s.host)}</div></td><td>${esc(s.deleted_by)}<div class="small">${date(s.deleted_at)}</div></td><td><button data-action="restore-service" data-id="${esc(s.id)}" aria-label="${esc(s.name)} 복원">복원</button></td></tr>`)) : '<p class="muted">표시할 삭제 서비스가 없습니다.</p>'}</section>`;
}

function assetHistory(events = []) {
  if (!events.length) return "";
  const actions = {ASSET_DELETED:"자산 삭제", ASSET_RESTORED:"자산 복원", SERVICE_DELETED:"서비스 삭제", SERVICE_RESTORED:"서비스 복원"};
  return `<details class="panel"><summary>삭제·복원 이력 · 최근 20건</summary>${table(["작업", "항목", "사용자", "시각"], events.map(e => `<tr><td>${esc(actions[e.action] || e.action)}</td><td>${esc(e.item_name)}</td><td>${esc(e.actor)}</td><td>${date(e.occurred_at)}</td></tr>`))}</details>`;
}

function confirmTrash(item, kind, trigger) {
  pendingTrash = {item, kind, trigger};
  const asset = kind === "asset", name = item.name || item.observed?.name || "이름 미입력";
  const dialog = $("#trash-dialog");
  dialog.innerHTML = `<form id="trash-confirm"><h2 id="trash-title">${asset ? "자산" : "서비스"} 삭제</h2><p><strong>${esc(name)}</strong>을(를) 휴지통으로 옮깁니다.</p>
    <p>${asset ? "점검 대상과 자산 대장에서 제외됩니다. 자산 정보·ELS 계약·서비스·점검 이력은 보존하며 휴지통에서 복원할 수 있습니다." : "서비스 대장과 자산 MD/XLSX에서 제외됩니다. 수기 정보와 관측 근거는 보존하며 휴지통에서 복원할 수 있습니다. 실제 서버의 서비스를 중지하거나 삭제하지 않습니다."}</p>
    ${!asset && $("#service-edit")?.dataset.dirty === "true" ? '<p class="note warn">편집 창의 저장하지 않은 입력은 버려집니다.</p>' : ""}
    ${asset ? '<label>확인을 위해 자산 이름을 그대로 입력하세요<input name="name" maxlength="120" autocomplete="off" required></label>' : ""}
    <p class="error" role="alert"></p><div class="form-actions"><button type="button" data-action="cancel-trash" autofocus>취소</button><button type="submit" class="danger" ${asset ? "disabled" : ""}>휴지통으로 이동</button></div></form>`;
  dialog.showModal();
  dialog.querySelector('[data-action="cancel-trash"]').focus();
}

function closeTrash() {
  if ($("#trash-confirm")?.dataset.saving === "true") return;
  const trigger = pendingTrash?.trigger;
  pendingTrash = null;
  $("#trash-dialog").close();
  if (trigger?.isConnected) trigger.focus();
}

document.addEventListener("input", event => {
  if (event.target.matches('#trash-confirm [name="name"]')) {
    $("#trash-confirm [type=submit]").disabled = event.target.value !== pendingTrash.item.name;
  }
});
document.addEventListener("cancel", event => {
  if (event.target.id === "trash-dialog") { event.preventDefault(); closeTrash(); }
}, true);

document.addEventListener("click", async event => {
  const button = event.target.closest("[data-action]");
  if (!button || button.disabled || !admin()) return;
  const action = button.dataset.action;
  if (action === "cancel-trash") return closeTrash();
  if (action === "refresh-trash") return render();
  if (action === "delete-asset") {
    if (hasAssetDraft()) { toast("입력 중인 정보를 먼저 저장하거나 취소하세요."); return; }
    confirmTrash({id:button.dataset.id, name:button.dataset.name, trash_revision:Number(button.dataset.revision)}, "asset", button);
  }
  if (action === "delete-service") {
    const service = assetDetail?.services.find(s => s.id === button.dataset.id);
    if (service) confirmTrash(service, "service", button);
  }
  if (action === "restore-asset" || action === "restore-service") {
    const asset = action === "restore-asset", item = (asset ? trashItems?.assets : trashItems?.services)?.find(i => i.id === button.dataset.id);
    if (!item) return;
    button.disabled = true;
    try {
      const path = "/admin/assets/" + encodeURIComponent(asset ? item.id : item.target_id) + (asset ? "" : "/services/" + encodeURIComponent(item.id)) + "/restore";
      const response = await api(path, {method:"POST", body:{revision:asset ? item.trash_revision : item.revision}});
      toast(response.message); await render();
    } catch (error) { toast(error.message); }
    finally { button.disabled = false; }
  }
});

document.addEventListener("submit", async event => {
  const form = event.target;
  if (form.id === "trash-search") { event.preventDefault(); trashQuery = form.elements.query.value; await render(); return; }
  if (form.id !== "trash-confirm" || !pendingTrash) return;
  event.preventDefault();
  if (form.dataset.saving === "true") return;
  const {item, kind} = pendingTrash, asset = kind === "asset";
  const target = asset ? item.id : assetDetail.asset.id;
  const body = asset ? {name:form.elements.name.value, revision:item.trash_revision} : {revision:item.revision};
  form.dataset.saving = "true";
  form.querySelectorAll("button, input").forEach(el => el.disabled = true);
  try {
    const path = "/admin/assets/" + encodeURIComponent(target) + (asset ? "" : "/services/" + encodeURIComponent(item.id));
    const response = await api(path, {method:"DELETE", body});
    form.dataset.saving = "false"; closeTrash();
    if (!asset) { editingService = null; $("#service-editor").close(); }
    toast(response.message);
    if (asset) location.hash = "#assets/trash"; else await render();
  } catch (error) {
    form.querySelector(".error").textContent = error.message;
  } finally {
    form.dataset.saving = "false";
    form.querySelectorAll("button, input").forEach(el => el.disabled = false);
  }
});
