<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const members = ref([])
const passes = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const tierFilter = ref('')
const tiers = ['普通', '银卡', '金卡']

const memberDialog = ref(false)
const saving = ref(false)
const memberForm = ref(blankMember())
function blankMember() { return { name: '', tier: '普通', phone: '' } }

const passDialog = ref(false)
const passSaving = ref(false)
const passError = ref('')
const passForm = ref({ memberId: null, memberName: '', validFrom: '', validTo: '', passType: '年卡' })
const passTypes = ['年卡', '季卡']

async function load() {
  loading.value = true; error.value = ''
  try {
    const [m, p] = await Promise.all([
      api.listMembers(tierFilter.value ? { tier: tierFilter.value } : {}),
      api.listSeasonPasses()
    ])
    members.value = m || []
    passes.value = p || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

const passMap = computed(() => {
  const map = {}
  for (const p of passes.value) map[p.memberId] = p
  return map
})

function passText(row) {
  const p = passMap.value[row.id]
  if (!p) return '未办卡'
  return `${p.passType}（${p.validFrom} ~ ${p.validTo}）`
}

function passStatus(row) {
  const p = passMap.value[row.id]
  return p ? p.status : ''
}

function statusTagType(s) {
  return s === '有效' ? 'success' : s === '未生效' ? 'info' : 'danger'
}

// 当前对话框是否为续期（会员已持卡）
const isRenew = computed(() => !!(passForm.value.memberId && passMap.value[passForm.value.memberId]))
const currentPass = computed(() => passMap.value[passForm.value.memberId] || null)

// 办卡/续期表单：日期区间校验（与后端规则一致，先本地拦截）
function checkPassDates() {
  if (!passForm.value.validFrom || !passForm.value.validTo) {
    passError.value = '请选择有效期起和有效期止'
    return false
  }
  if (passForm.value.validFrom >= passForm.value.validTo) {
    passError.value = '有效期起必须早于有效期止'
    return false
  }
  return true
}

// 有效期止不能早于或等于有效期起
function disableInvalidTo(d) {
  if (!passForm.value.validFrom) return false
  return d.getTime() <= new Date(passForm.value.validFrom + 'T00:00:00').getTime()
}

function openCreateMember() { memberForm.value = blankMember(); memberDialog.value = true; tip.value = ''; error.value = '' }
async function saveMember() {
  saving.value = true; error.value = ''
  try {
    await api.createMember(memberForm.value)
    tip.value = `会员 ${memberForm.value.name} 已建档`
    memberDialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

function openIssue(row) {
  passForm.value = { memberId: row.id, memberName: row.name, validFrom: '', validTo: '', passType: '年卡' }
  error.value = ''; tip.value = ''; passError.value = ''; passDialog.value = true
}
async function savePass() {
  if (!checkPassDates()) return
  passSaving.value = true; passError.value = ''
  try {
    await api.issueSeasonPass(passForm.value.memberId, {
      validFrom: passForm.value.validFrom,
      validTo: passForm.value.validTo,
      passType: passForm.value.passType
    })
    tip.value = `${passForm.value.memberName} 办卡成功`
    passDialog.value = false
    await load()
  } catch (e) { passError.value = e.message } finally { passSaving.value = false }
}

function openRenew(row) {
  const p = passMap.value[row.id]
  passForm.value = {
    memberId: row.id, memberName: row.name,
    validFrom: p ? p.validFrom : '', validTo: p ? p.validTo : '',
    passType: p ? p.passType : '年卡'
  }
  error.value = ''; tip.value = ''; passError.value = ''; passDialog.value = true
}
async function renewPass() {
  if (!checkPassDates()) return
  passSaving.value = true; passError.value = ''
  try {
    await api.renewSeasonPass(passForm.value.memberId, {
      validFrom: passForm.value.validFrom,
      validTo: passForm.value.validTo,
      passType: passForm.value.passType
    })
    tip.value = `${passForm.value.memberName} 已续期，乘坐核验立即按新有效期生效`
    passDialog.value = false
    await load()
  } catch (e) { passError.value = e.message } finally { passSaving.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">会员与年卡 · 共享主键</span>
        <span class="panel-desc">会员等级 普通/银卡/金卡（等级不等于有效卡）；年卡与会员共享主键（season_pass.member_id = member.id）；卡片状态按当前日期分为 有效 / 未生效 / 已过期，乘坐核验实时读取有效期</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">会员数</div><div class="v">{{ members.length }}</div></div>
        <div class="stat"><div class="k">已办卡</div><div class="v">{{ passes.length }}</div></div>
        <div class="stat"><div class="k">当前有效</div><div class="v">{{ passes.filter(p => p.status === '有效').length }}</div></div>
        <div class="stat"><div class="k">金卡</div><div class="v">{{ members.filter(m => m.tier === '金卡').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="tierFilter" placeholder="全部等级" clearable style="width: 130px" @change="load">
          <el-option v-for="t in tiers" :key="t" :label="t" :value="t" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreateMember">新建会员</el-button>
      </div>

      <el-table :data="members" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="姓名" min-width="110" />
        <el-table-column prop="tier" label="等级" width="90">
          <template #default="{ row }">
            <el-tag :type="row.tier === '金卡' ? 'warning' : row.tier === '银卡' ? 'info' : ''">{{ row.tier }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column label="年卡/季卡" min-width="260">
          <template #default="{ row }">
            <span>{{ passText(row) }}</span>
            <el-tag v-if="passStatus(row)" size="small" :type="statusTagType(passStatus(row))" style="margin-left: 8px">
              {{ passStatus(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!passMap[row.id]" size="small" type="primary" @click="openIssue(row)">办卡</el-button>
            <el-button v-else size="small" @click="openRenew(row)">续期</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="memberDialog" title="新建会员" width="460px">
      <el-form :model="memberForm" label-width="90px" class="dialog-form">
        <el-form-item label="姓名"><el-input v-model="memberForm.name" /></el-form-item>
        <el-form-item label="等级">
          <el-radio-group v-model="memberForm.tier">
            <el-radio v-for="t in tiers" :key="t" :value="t">{{ t }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="手机号"><el-input v-model="memberForm.phone" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMember">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passDialog" :title="`年卡/季卡 · ${passForm.memberName}`" width="480px">
      <div v-if="passError" class="err" style="margin-bottom: 10px">{{ passError }}</div>
      <el-form label-width="90px" class="dialog-form">
        <el-form-item v-if="isRenew" label="当前状态">
          <el-tag :type="statusTagType(currentPass.status)">{{ currentPass.status }}</el-tag>
          <span style="margin-left: 8px; font-size: 12px; color: var(--muted)">
            {{ currentPass.passType }} {{ currentPass.validFrom }} ~ {{ currentPass.validTo }}
          </span>
        </el-form-item>
        <el-form-item label="卡类型">
          <el-radio-group v-model="passForm.passType">
            <el-radio v-for="t in passTypes" :key="t" :value="t">{{ t }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期起"><el-date-picker v-model="passForm.validFrom" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="有效期止"><el-date-picker v-model="passForm.validTo" type="date" value-format="YYYY-MM-DD" :disabled-date="disableInvalidTo" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passDialog = false">取消</el-button>
        <el-button type="primary" :loading="passSaving" @click="isRenew ? renewPass() : savePass()">
          {{ isRenew ? '续期' : '办卡' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
