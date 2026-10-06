<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const statuses = ['开放', '检修', '关闭']

const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())
function blank() {
  return { rideCode: '', rideName: '', minHeightCm: '', maxAge: '', status: '开放' }
}

const boardDialog = ref(false)
const boarding = ref(false)
const boardForm = ref({ id: null, rideName: '', heightCm: '', age: '', memberId: null })
const boardResult = ref(null)
const boardError = ref('')
const members = ref([])
const passes = ref([])

// ---------------- 检修安全复核与放行 ----------------
const checkItems = [
  { key: 'brakeOk', label: '制动系统', short: '制动' },
  { key: 'restraintOk', label: '约束装置', short: '约束' },
  { key: 'emergencyStopOk', label: '紧急停机', short: '停机' },
  { key: 'trialRunOk', label: '空载试运行', short: '空载' }
]

const inspDialog = ref(false)
const inspRide = ref(null)
const activeBatch = ref(null)
const batches = ref([])
const inspLoading = ref(false)
const acting = ref(false)
const editing = ref(false)
const inspError = ref('')
const inspTip = ref('')
const itemForm = ref({ brakeOk: null, restraintOk: null, emergencyStopOk: null, trialRunOk: null, remark: '' })
const inspectorName = ref(localStorage.getItem('inspectorName') || '')
const releaserName = ref(localStorage.getItem('releaserName') || '')

const allPassed = computed(() => checkItems.every(c => itemForm.value[c.key] === true))

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listRides(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      ...form.value,
      minHeightCm: form.value.minHeightCm === '' ? null : Number(form.value.minHeightCm),
      maxAge: form.value.maxAge === '' ? null : Number(form.value.maxAge)
    }
    await api.createRide(d)
    tip.value = `设施 ${d.rideCode} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function transfer(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferRide(row.id, target)
    tip.value = target === '检修'
      ? `${row.rideName} 已转入检修，本次安全复核批次已生成`
      : `${row.rideName} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

function openBoard(row) {
  boardForm.value = { id: row.id, rideName: row.rideName, heightCm: '', age: '', memberId: null }
  boardResult.value = null
  boardError.value = ''
  boardDialog.value = true
  error.value = ''; tip.value = ''
  // 每次打开实时拉取会员与卡片状态：续期/办卡后立即反映，不用旧缓存
  loadMembersAndPasses()
}
async function loadMembersAndPasses() {
  try {
    const [m, p] = await Promise.all([api.listMembers({}), api.listSeasonPasses()])
    members.value = m || []
    passes.value = p || []
  } catch (e) { /* 会员列表加载失败不阻断散客核验 */ }
}

const passByMember = computed(() => {
  const map = {}
  for (const p of passes.value) map[p.memberId] = p
  return map
})

// 选中会员的卡片状态提示（仅供参考，最终以服务端实时核验为准）
const selectedPassHint = computed(() => {
  const mid = boardForm.value.memberId
  if (!mid) return ''
  const p = passByMember.value[mid]
  if (!p) return '该会员未办理年卡/季卡，选择后核验将被拒绝'
  return `${p.passType} ${p.validFrom} ~ ${p.validTo}（${p.status}）`
})
const selectedPassHintType = computed(() => {
  const p = passByMember.value[boardForm.value.memberId]
  if (!p) return 'danger'
  return p.status === '有效' ? 'success' : p.status === '未生效' ? 'info' : 'danger'
})

async function doBoard() {
  boarding.value = true; boardError.value = ''; boardResult.value = null
  try {
    boardResult.value = await api.boardRide(
      boardForm.value.id,
      boardForm.value.heightCm === '' ? null : Number(boardForm.value.heightCm),
      boardForm.value.age === '' ? null : Number(boardForm.value.age),
      boardForm.value.memberId ? Number(boardForm.value.memberId) : null
    )
  } catch (e) {
    // 拒绝原因直接展示服务端 message，与接口返回保持一致
    boardError.value = e.message
  } finally { boarding.value = false }
}

// ---------------- 复核对话框 ----------------

async function openInspect(row) {
  inspRide.value = row
  activeBatch.value = null
  batches.value = []
  editing.value = false
  inspError.value = ''; inspTip.value = ''
  inspDialog.value = true
  await refreshBatches()
}

async function refreshBatches() {
  inspLoading.value = true
  try {
    const [active, history] = await Promise.all([
      api.activeInspection(inspRide.value.id),
      api.listInspections(inspRide.value.id)
    ])
    activeBatch.value = active || null
    batches.value = history || []
    if (activeBatch.value && activeBatch.value.status === '填写中') {
      fillForm(activeBatch.value)
      editing.value = false
    }
  } catch (e) { inspError.value = e.message } finally { inspLoading.value = false }
}

function fillForm(b) {
  itemForm.value = {
    brakeOk: b.brakeOk, restraintOk: b.restraintOk,
    emergencyStopOk: b.emergencyStopOk, trialRunOk: b.trialRunOk,
    remark: b.remark || ''
  }
}

async function ensureBatch() {
  acting.value = true; inspError.value = ''
  try {
    await api.ensureInspection(inspRide.value.id)
    await refreshBatches()
  } catch (e) { inspError.value = e.message } finally { acting.value = false }
}

function startEdit() {
  fillForm(activeBatch.value)
  editing.value = true
  inspTip.value = ''
  inspError.value = ''
}

async function saveItems(silent) {
  const res = await api.saveInspectionItems(activeBatch.value.id, itemForm.value)
  if (!silent) inspTip.value = '复核项已保存'
  return res
}

async function doSaveItems() {
  acting.value = true; inspError.value = ''; inspTip.value = ''
  try {
    await saveItems(false)
    editing.value = false
    await refreshBatches()
  } catch (e) { inspError.value = e.message } finally { acting.value = false }
}

async function submitBatch() {
  inspError.value = ''; inspTip.value = ''
  if (!allPassed.value) {
    inspError.value = '所有必检项（制动系统、约束装置、紧急停机、空载试运行）合格后才能提交'
    return
  }
  if (!inspectorName.value.trim()) {
    inspError.value = '请填写安全员（提交人）姓名'
    return
  }
  acting.value = true
  try {
    // 退回修改场景：保存会使原批次失效并生成新批次，须用返回的最新批次提交
    const saved = await saveItems(true)
    await api.submitInspection(saved.id, inspectorName.value.trim())
    localStorage.setItem('inspectorName', inspectorName.value.trim())
    inspTip.value = '复核已提交，待负责人放行（提交人不能担任放行人）'
    editing.value = false
    await refreshBatches()
  } catch (e) {
    inspError.value = e.message
    await refreshBatches()
  } finally { acting.value = false }
}

async function releaseBatch() {
  inspError.value = ''; inspTip.value = ''
  if (!releaserName.value.trim()) {
    inspError.value = '请填写负责人（放行人）姓名'
    return
  }
  if (activeBatch.value && releaserName.value.trim() === activeBatch.value.submittedBy) {
    inspError.value = '提交人不能担任最终放行人，安全员与放行人须分离'
    return
  }
  acting.value = true
  try {
    await api.releaseInspection(activeBatch.value.id, releaserName.value.trim())
    localStorage.setItem('releaserName', releaserName.value.trim())
    tip.value = `${inspRide.value.rideName} 复核通过，已恢复开放，乘坐校验即时按最新限制生效`
    inspDialog.value = false
    await load()
  } catch (e) {
    // 未获得放行权（他人已放行/批次已失效等）：展示设施当前状态，绝不在本地回滚
    inspError.value = e.message
    await load()
    syncInspRide()
    await refreshBatches()
  } finally { acting.value = false }
}

// 放行冲突/状态变化后，用主列表的最新数据刷新对话框头部的设施状态
function syncInspRide() {
  const fresh = rows.value.find(r => r.id === inspRide.value?.id)
  if (fresh) inspRide.value = fresh
}

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '—' }
function fmtOk(v) { return v === true ? '合格' : v === false ? '不合格' : '未检' }
function okClass(v) { return v === true ? 'ok-pass' : v === false ? 'ok-fail' : 'ok-none' }
function fmtItems(b) {
  return checkItems.map(c => `${c.short}${b[c.key] === true ? '✓' : b[c.key] === false ? '✗' : '–'}`).join(' ')
}
function batchTagType(s) {
  return s === '已放行' ? 'success' : s === '待放行' ? 'warning' : s === '已失效' ? 'danger' : 'info'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">游乐设施 · 乘坐校验与安全复核</span>
        <span class="panel-desc">乘坐前校验：会员身份（可选）→ 卡片有效期（含首尾）→ 设施开放 → 身高达标 → 年龄不超上限，四类条件同时满足才可乘坐；检修后恢复开放须完成安全复核并由负责人放行</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">设施数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">开放</div><div class="v">{{ rows.filter(r => r.status === '开放').length }}</div></div>
        <div class="stat"><div class="k">检修</div><div class="v">{{ rows.filter(r => r.status === '检修').length }}</div></div>
        <div class="stat"><div class="k">关闭</div><div class="v">{{ rows.filter(r => r.status === '关闭').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建设施</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="rideCode" label="编号" width="100" />
        <el-table-column prop="rideName" label="名称" min-width="130" />
        <el-table-column prop="minHeightCm" label="最低身高(cm)" width="120" />
        <el-table-column prop="maxAge" label="最大年龄" width="100" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '开放' ? 'success' : row.status === '检修' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openBoard(row)">乘坐校验</el-button>
            <template v-if="row.status === '检修'">
              <el-button size="small" type="warning" @click="openInspect(row)">安全复核 / 放行</el-button>
            </template>
            <template v-else>
              <el-button size="small" @click="transfer(row, '检修')">检修</el-button>
              <el-button size="small" :disabled="row.status === '关闭'" @click="transfer(row, '关闭')">关闭</el-button>
              <el-button size="small" type="success" plain :disabled="row.status !== '关闭'" @click="transfer(row, '开放')">开放</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建设施" width="520px">
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="设施编号"><el-input v-model="form.rideCode" placeholder="如 R-009" /></el-form-item>
        <el-form-item label="设施名称"><el-input v-model="form.rideName" /></el-form-item>
        <el-form-item label="最低身高(cm)"><el-input v-model="form.minHeightCm" type="number" placeholder="不限留空" /></el-form-item>
        <el-form-item label="最大年龄"><el-input v-model="form.maxAge" type="number" placeholder="不限留空" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in statuses" :key="s" :value="s">{{ s }}</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="boardDialog" :title="`乘坐校验 · ${boardForm.rideName}`" width="500px">
      <div v-if="boardError" class="err" style="margin-bottom: 10px">{{ boardError }}</div>
      <el-form label-width="110px" class="dialog-form">
        <el-form-item label="会员（可选）">
          <el-select v-model="boardForm.memberId" placeholder="散客（不选择会员）" clearable filterable style="width: 100%">
            <el-option v-for="m in members" :key="m.id" :label="`${m.name}（${m.tier}）`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="selectedPassHint" label="卡片状态">
          <el-tag :type="selectedPassHintType">{{ selectedPassHint }}</el-tag>
        </el-form-item>
        <el-form-item label="游客身高(cm)"><el-input v-model="boardForm.heightCm" type="number" /></el-form-item>
        <el-form-item label="游客年龄"><el-input v-model="boardForm.age" type="number" /></el-form-item>
        <el-form-item v-if="boardResult" label="结果">
          <div class="board-ok">
            <div class="board-ok-title">核验通过，可以乘坐 🎉</div>
            <div v-for="(c, i) in boardResult.checks" :key="i" class="board-check">✓ {{ c }}</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="boardDialog = false">关闭</el-button>
        <el-button type="primary" :loading="boarding" @click="doBoard">核验</el-button>
      </template>
    </el-dialog>

    <!-- 检修安全复核与放行 -->
    <el-dialog v-model="inspDialog" :title="`安全复核 · ${inspRide ? inspRide.rideName : ''}`" width="720px">
      <div v-if="inspRide" v-loading="inspLoading">
        <div class="insp-head">
          <span>设施状态：
            <el-tag :type="inspRide.status === '开放' ? 'success' : inspRide.status === '检修' ? 'warning' : 'info'">
              {{ inspRide.status }}
            </el-tag>
          </span>
          <span class="insp-limit">当前限制：身高 ≥ {{ inspRide.minHeightCm ?? '不限' }}cm ／ 年龄 ≤ {{ inspRide.maxAge ?? '不限' }} 岁</span>
        </div>

        <div v-if="inspError" class="err">{{ inspError }}</div>
        <div v-if="inspTip" class="ok-tip">{{ inspTip }}</div>

        <!-- 无进行中批次 -->
        <div v-if="!activeBatch" class="insp-empty">
          <p>当前没有进行中的复核批次。</p>
          <el-button v-if="inspRide.status === '检修'" type="warning" :loading="acting" @click="ensureBatch">
            发起本次安全复核批次
          </el-button>
        </div>

        <!-- 进行中批次 -->
        <div v-else class="insp-card">
          <div class="insp-card-head">
            <span class="insp-no">{{ activeBatch.batchNo }}</span>
            <el-tag :type="batchTagType(activeBatch.status)">{{ activeBatch.status }}</el-tag>
          </div>

          <!-- 填写中 / 退回修改：可编辑 -->
          <template v-if="activeBatch.status === '填写中' || editing">
            <el-form label-width="110px" class="dialog-form">
              <el-form-item v-for="c in checkItems" :key="c.key" :label="c.label">
                <el-radio-group v-model="itemForm[c.key]">
                  <el-radio :value="true">合格</el-radio>
                  <el-radio :value="false">不合格</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="备注"><el-input v-model="itemForm.remark" placeholder="选填" /></el-form-item>
              <el-form-item label="安全员">
                <el-input v-model="inspectorName" placeholder="提交人姓名（提交后不能担任放行人）" style="width: 280px" />
              </el-form-item>
            </el-form>
            <div class="insp-actions">
              <el-button :loading="acting" @click="doSaveItems">保存记录</el-button>
              <el-button type="primary" :loading="acting" :disabled="!allPassed" @click="submitBatch">提交复核</el-button>
            </div>
            <div class="insp-note">提交条件：四项必检全部「合格」；提交后再修改复核项，原批次将标记失效（历史保留）。</div>
          </template>

          <!-- 待放行：只读 + 负责人放行 -->
          <template v-else-if="activeBatch.status === '待放行'">
            <div class="insp-items">
              <span v-for="c in checkItems" :key="c.key" class="insp-item">
                {{ c.label }}<b :class="okClass(activeBatch[c.key])">{{ fmtOk(activeBatch[c.key]) }}</b>
              </span>
            </div>
            <div class="insp-meta">
              提交人：{{ activeBatch.submittedBy }} · {{ fmtTime(activeBatch.submittedAt) }}<br>
              提交时限制快照：身高 ≥ {{ activeBatch.snapshotMinHeightCm ?? '不限' }}cm ／ 年龄 ≤ {{ activeBatch.snapshotMaxAge ?? '不限' }} 岁
              <span v-if="activeBatch.remark">；备注：{{ activeBatch.remark }}</span>
            </div>
            <el-form label-width="110px" class="dialog-form" style="margin-top: 10px">
              <el-form-item label="负责人">
                <el-input v-model="releaserName" placeholder="放行人姓名（不能是提交人）" style="width: 280px" />
              </el-form-item>
            </el-form>
            <div class="insp-actions">
              <el-button :loading="acting" @click="startEdit">退回修改复核项</el-button>
              <el-button type="success" :loading="acting" :disabled="!releaserName.trim()" @click="releaseBatch">
                确认恢复开放
              </el-button>
            </div>
            <div class="insp-note">放行条件：设施仍处于检修、复核内容未过期、放行人 ≠ 提交人；并发或重复点击只生效一次。</div>
          </template>
        </div>

        <!-- 历史批次：永久可追溯 -->
        <div v-if="batches.length" class="insp-history">
          <div class="insp-history-title">复核历史（失效批次保留可追溯，不会被新批次覆盖）</div>
          <el-table :data="batches" size="small" border>
            <el-table-column prop="batchNo" label="批次号" width="150" />
            <el-table-column label="状态" width="76">
              <template #default="{ row }">
                <el-tag size="small" :type="batchTagType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="检查结果" width="130">
              <template #default="{ row }">{{ fmtItems(row) }}</template>
            </el-table-column>
            <el-table-column prop="submittedBy" label="提交人" width="76">
              <template #default="{ row }">{{ row.submittedBy || '—' }}</template>
            </el-table-column>
            <el-table-column prop="releasedBy" label="放行人" width="76">
              <template #default="{ row }">{{ row.releasedBy || '—' }}</template>
            </el-table-column>
            <el-table-column label="失效原因 / 时间" min-width="150">
              <template #default="{ row }">
                <span v-if="row.status === '已失效'">{{ row.invalidateReason }}</span>
                <span v-else-if="row.releasedAt">放行于 {{ fmtTime(row.releasedAt) }}</span>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.board-ok {
  line-height: 1.7;
}
.board-ok-title {
  color: #1e8e4e;
  font-weight: 600;
}
.board-check {
  font-size: 12px;
  color: var(--muted);
}
.insp-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}
.insp-limit {
  font-size: 12px;
  color: var(--muted);
}
.insp-empty {
  text-align: center;
  padding: 18px 0;
  color: var(--muted);
}
.insp-card {
  border: 1px solid #ffe49a;
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 14px;
  background: #fffdf4;
}
.insp-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.insp-no {
  font-weight: 700;
}
.insp-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.insp-note {
  margin-top: 8px;
  font-size: 12px;
  color: var(--muted);
}
.insp-items {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.insp-item b {
  margin-left: 6px;
}
.ok-pass { color: #1e8e4e; }
.ok-fail { color: #c0392b; }
.ok-none { color: var(--muted); }
.insp-meta {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.7;
}
.insp-history-title {
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 8px;
}
</style>
