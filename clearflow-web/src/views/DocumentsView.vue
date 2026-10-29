<script setup>
import { ref, reactive, onMounted, onUnmounted, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { useUiStore } from '../stores/ui'
import { usePermStore } from '../stores/perm'
import { useMembersStore } from '../stores/members'
import LoadingMask from '../components/LoadingMask.vue'
import AppModal from '../components/AppModal.vue'
import AppConfirm from '../components/AppConfirm.vue'
import AppPager from '../components/AppPager.vue'
import AppMdEditor from '../components/AppMdEditor.vue'
import AppMdPreview from '../components/AppMdPreview.vue'
import PageCreateButton from '../components/PageCreateButton.vue'
import { docFileName, downloadDocMarkdown, downloadBlob } from '../utils/docExport'

const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const perm = usePermStore()
const members = useMembersStore() // 作者名映射（共享）
// 文档写操作（新建/编辑/分类管理）需「文档管理」编辑级；删除单篇文档需管理级（与后端 DELETE 接口同口径）
const canWrite = computed(() => perm.canWrite('文档管理'))
const canManage = computed(() => perm.canManage('文档管理'))
const loading = ref(true)
const rows = ref([]) // 当前页文档（服务端分页，行内不含正文）
const total = ref(0)
const docStats = ref(null) // 分类文档数（后端 GROUP BY）
const mmap = computed(() => members.map)

// 筛选/分页连打时只认最后一次响应
let fetchSeq = 0
async function fetchData() {
  const seq = ++fetchSeq
  const [d, cats, s] = await Promise.all([
    api.getDocs(filterParams.value, page.value, pageSize.value),
    api.getDocCategories(),
    api.getDocStats()
  ])
  if (seq !== fetchSeq) return
  rows.value = d.list || []
  total.value = d.total || 0
  categories.value = [...cats]
  docStats.value = s
  if (!rows.value.length && page.value > 1) page.value = 1 // 删除后页码越界回收
}
onMounted(async () => {
  window.addEventListener('beforeunload', onBeforeUnload)
  await members.load() // 作者名映射需要的成员数据（共享）
  if (route.query.doc) viewId.value = String(route.query.doc)
  await fetchData()
  loading.value = false
  if (viewId.value) await openView({ id: viewId.value }) // 深链：正文按 id 异步回填
})
onUnmounted(() => window.removeEventListener('beforeunload', onBeforeUnload))
watch(() => ui.refreshTick, fetchData)

// ===== 筛选（分类 / 更新人 / 标题与正文关键词，全部下推服务端）=====
const categories = ref([])
const fCat = ref('all')
const fOwner = ref('')
const fKw = ref('')
const mmapArr = computed(() => Object.values(mmap.value))
const filterParams = computed(() => ({
  q: fKw.value.trim(),
  category: fCat.value === 'all' ? '' : fCat.value,
  owner: fOwner.value
}))

// ===== 分页（服务端分页，paged 即当前页）=====
const page = ref(1)
const pageSize = ref(10)
const paged = computed(() => rows.value)
watch([fCat, fOwner, fKw], () => {
  // 回第一页由 page 监听取数，已经是第一页时直接取数，避免重复请求
  if (page.value === 1) fetchData().catch(() => {})
  else page.value = 1
})
watch([page, pageSize], () => fetchData().catch(() => {}))

// ===== 页面视图状态：列表 / 查看（全页+目录）/ 编辑（全宽编辑器）=====
const mode = ref('list') // 'list' | 'view' | 'edit'
const viewId = ref(null)
const viewDoc = ref(null) // 正文按 id 从详情接口取
const toc = ref([]) // 导航目录（由预览回传的标题结构）
const exporting = ref('') // '' | 'download' | 'pdf'，导出中禁用按钮并显示进度文案
const exportMsg = ref('')
let viewSeq = 0

async function fetchDoc(id) {
  const seq = ++viewSeq
  const d = await api.getDoc(id)
  if (seq !== viewSeq || String(viewId.value) !== String(id)) return
  viewDoc.value = d
}

// 视图切换时回到顶部，避免停留在上一视图的滚动位置导致页头按钮不可见
const scrollTop = () => window.scrollTo(0, 0)
async function openView(d) {
  viewId.value = d.id
  if (!d.content) viewDoc.value = { ...d, content: '' } // 列表行/深链先渲染标题，正文异步回填
  toc.value = []
  exportMsg.value = ''
  mode.value = 'view'
  scrollTop()
  await fetchDoc(d.id)
}
function backToList() {
  mode.value = 'list'
  viewId.value = null
  viewDoc.value = null
  scrollTop()
}
function jumpTo(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// ===== 下载：Markdown 原文（含图片时自动打包 zip）/ 导出 PDF（后端生成，带目录与页码）=====
async function downloadMd() {
  const d = viewDoc.value
  if (!d || exporting.value) return
  exportMsg.value = ''
  exporting.value = 'download'
  try {
    const r = await downloadDocMarkdown(d)
    if (r.missing) exportMsg.value = `有 ${r.missing} 张图片读取失败，文档内已保留原地址`
  } catch (e) {
    exportMsg.value = `下载失败：${e?.message || e}`
  } finally {
    exporting.value = ''
  }
}
async function exportPdf() {
  const d = viewDoc.value
  if (!d || exporting.value) return
  exportMsg.value = ''
  exporting.value = 'pdf'
  try {
    const blob = await api.getDocPdf(d.id)
    downloadBlob(docFileName(d.title, 'pdf'), blob)
  } catch (e) {
    exportMsg.value = `导出失败：${e?.message || e}`
  } finally {
    exporting.value = ''
  }
}

// 查看态写入 URL（?doc=ID），浏览器前进/后退可回到对应文档
function syncQuery() {
  const want = mode.value === 'view' && viewId.value ? { doc: viewId.value } : {}
  if (String(route.query.doc ?? '') !== String(want.doc ?? '')) {
    router.replace({ path: route.path, query: want }).catch(() => {})
  }
}
watch([mode, viewId], syncQuery)
watch(
  () => route.query.doc,
  async (id) => {
    if (loading.value) return
    if (!id) {
      if (mode.value === 'view') {
        mode.value = 'list'
        viewId.value = null
        viewDoc.value = null
        scrollTop()
      }
      return
    }
    if (String(id) === String(viewId.value) && mode.value === 'view') return
    viewId.value = String(id)
    mode.value = 'view'
    scrollTop()
    await fetchDoc(id)
  }
)

// ===== 编辑视图 =====
const editForm = reactive({ id: null, title: '', category: '产品文档', content: '' })
const editErr = ref('')
const saving = ref(false)

// 未保存修改：离开编辑态前确认，关闭页签时由浏览器提示
const snapshot = ref('')
const formSnapshot = () => JSON.stringify([editForm.title, editForm.category, editForm.content])
const dirty = computed(() => mode.value === 'edit' && formSnapshot() !== snapshot.value)
const confirmDiscard = ref(false)
function onBeforeUnload(e) {
  if (!dirty.value) return
  e.preventDefault()
  e.returnValue = ''
}
function requestBackToList() {
  if (dirty.value) confirmDiscard.value = true
  else backToList()
}

// ===== 分类管理弹窗（新增 / 重命名 / 删除）=====
const catMgrOpen = ref(false)
const mgrName = ref('')
const mgrErr = ref('')
const renaming = ref(null) // 正在重命名的原分类名
const renameVal = ref('')
let renameInput = null // 行内重命名输入框（函数 ref）

const docCount = (c) => docStats.value?.byCategory?.[c] || 0
function openCatMgr() {
  mgrName.value = ''
  mgrErr.value = ''
  renaming.value = null
  catMgrOpen.value = true
}
async function mgrAdd() {
  const n = mgrName.value.trim()
  if (!n) { mgrErr.value = '请输入分类名称'; return }
  if (categories.value.includes(n)) { mgrErr.value = `「${n}」已存在`; return }
  const res = await api.addDocCategory(n)
  categories.value = [...res.categories]
  mgrName.value = ''
  mgrErr.value = ''
}
async function startRename(c) {
  renaming.value = c
  renameVal.value = c
  mgrErr.value = ''
  await nextTick()
  renameInput?.focus()
  renameInput?.select()
}
async function mgrRenameConfirm() {
  const n = renameVal.value.trim()
  if (!n) { mgrErr.value = '分类名称不能为空'; return }
  if (n !== renaming.value && categories.value.includes(n)) { mgrErr.value = `「${n}」已存在`; return }
  await api.updateDocCategory(renaming.value, n)
  renaming.value = null
  mgrErr.value = ''
  await fetchData() // 重命名会同步更新该分类下文档，一并刷新
}
async function mgrDelete(c) {
  const res = await api.deleteDocCategory(c)
  if (!res.ok) {
    mgrErr.value = `「${c}」下还有 ${res.used} 篇文档，请先将这些文档改为其他分类后再删除`
    return
  }
  categories.value = [...res.categories]
  mgrErr.value = ''
}

// ===== 上传 Markdown 文档：本地读取文本，走新建文档接口 =====
const uploadInput = ref(null)
const uploadOpen = ref(false)
const uploadFile = ref(null) // { name, size, content }
const uploadCat = ref('')
const uploadErr = ref('')
const uploading = ref(false)
const canUpload = computed(() => !!uploadFile.value && !!uploadCat.value && !uploading.value)
const fmtSize = n => (n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(2)} MB`)
const fileTitle = n => n.replace(/\.(md|markdown)$/i, '').trim().slice(0, 255)

function pickUpload() {
  uploadInput.value.value = '' // 允许重复选择同一个文件
  uploadInput.value.click()
}
async function onFilePick(e) {
  const f = e.target.files?.[0]
  if (!f) return
  uploadFile.value = null
  uploadErr.value = ''
  uploadCat.value = fCat.value !== 'all' && categories.value.includes(fCat.value) ? fCat.value : categories.value[0] || ''
  if (!/\.(md|markdown)$/i.test(f.name)) uploadErr.value = '仅支持 .md / .markdown 文件'
  else if (!fileTitle(f.name)) uploadErr.value = '文件名不能作为标题，请重命名后再上传'
  else if (f.size > 2 * 1024 * 1024) uploadErr.value = '文件不能超过 2 MB'
  else if (!categories.value.length) uploadErr.value = '暂无文档分类，请先点击「管理分类」创建'
  if (uploadErr.value) {
    uploadOpen.value = true
    return
  }
  let content = ''
  try {
    content = (await f.text()).replace(/^\uFEFF/, '') // 去掉编辑器可能写入的 BOM
  } catch {
    uploadErr.value = '文件读取失败，请重试'
    uploadOpen.value = true
    return
  }
  if (!content.trim()) {
    uploadErr.value = '文件内容为空'
    uploadOpen.value = true
    return
  }
  uploadFile.value = { name: f.name, size: f.size, content }
  uploadOpen.value = true
}
async function confirmUpload() {
  if (!canUpload.value) return
  uploading.value = true
  uploadErr.value = ''
  try {
    const res = await api.createDoc({
      title: fileTitle(uploadFile.value.name),
      category: uploadCat.value,
      content: uploadFile.value.content
    })
    uploadOpen.value = false
    await fetchData()
    await openView({ id: res.id, title: fileTitle(uploadFile.value.name) }) // 直接打开阅读页，便于确认解析结果  } catch (err) {
    uploadErr.value = `上传失败：${err?.message || err}`
  } finally {
    uploading.value = false
  }
}

function startCreate() {
  Object.assign(editForm, { id: null, title: '', category: categories.value[0] || '产品文档', content: '' })
  editErr.value = ''
  viewId.value = null
  mode.value = 'edit'
  snapshot.value = formSnapshot()
  scrollTop()
}
async function startEdit(d) {
  // 列表行不含正文，从列表直接进编辑时先按 id 取回全文
  const full = d.content ? d : await api.getDoc(d.id)
  Object.assign(editForm, { id: full.id, title: full.title, category: full.category, content: full.content })
  editErr.value = ''
  viewId.value = null
  mode.value = 'edit'
  snapshot.value = formSnapshot()
  scrollTop()
}
async function saveDoc() {
  if (!editForm.title.trim()) {
    editErr.value = '请输入文档标题'
    return
  }
  saving.value = true
  try {
    const data = { title: editForm.title.trim(), category: editForm.category, content: editForm.content }
    if (editForm.id) await api.updateDoc(editForm.id, data)
    else await api.createDoc(data)
    await fetchData()
    mode.value = 'list'
    scrollTop()
  } finally {
    saving.value = false
  }
}

// 页面头部「新建文档」入口（PageCreateButton），直接进入编辑视图
watch(() => ui.activeCreate, v => {
  if (v === 'document') {
    startCreate()
    ui.closeCreate()
  }
})

// ===== 删除确认（列表行 / 查看页共用，选中对象为 viewId）=====
const confirmDel = ref(false)
async function doDelete() {
  const id = viewId.value
  confirmDel.value = false
  viewId.value = null
  viewDoc.value = null
  mode.value = 'list'
  scrollTop()
  await api.deleteDoc(id)
  await fetchData()
}

const charCount = c => (c || '').replace(/\s/g, '').length
</script>

<template>
  <LoadingMask v-if="loading" />
  <template v-else>
    <!-- ===== 编辑视图：页面内全宽 Markdown 编辑器 ===== -->
    <template v-if="mode === 'edit'">
      <div class="page-head rise d1">
        <div>
          <h1>{{ editForm.id ? '编辑文档' : '新建文档' }}</h1>
          <div class="page-meta">Markdown 格式，所见即所得编辑，保存后返回文档列表</div>
        </div>
        <div class="page-right">
          <button class="ghost-btn" @click="requestBackToList">取消</button>
          <button class="btn" :disabled="saving" @click="saveDoc">
            {{ saving ? '保存中…' : '保存文档' }}
          </button>
        </div>
      </div>

      <div class="card edit-card rise d2">
        <input class="doc-title" v-model="editForm.title" placeholder="输入文档标题…"
          @input="editErr = ''" @keydown.enter="saveDoc">
        <div v-if="editErr" class="field-error" style="margin-bottom:8px">{{ editErr }}</div>
        <div class="edit-meta">
          <span class="filter-label">分类</span>
          <select class="filter-select" v-model="editForm.category">
            <option v-for="c in categories" :key="c">{{ c }}</option>
          </select>
          <span class="meta-tip">支持 Markdown 语法，工具栏可切换所见即所得 / 分屏 / 预览模式</span>
        </div>
        <AppMdEditor v-model="editForm.content" :min-height="460" placeholder="开始撰写文档内容…" />
      </div>
    </template>

    <!-- ===== 查看视图：全页阅读 + 右侧导航目录 ===== -->
    <template v-else-if="mode === 'view' && viewDoc">
      <div class="page-head rise d1">
        <div>
          <button class="back-btn" @click="backToList">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 18l-6-6 6-6" /></svg>
            返回列表
          </button>
          <h1>{{ viewDoc.title }}</h1>
          <div class="page-meta">
            {{ viewDoc.category }} · {{ mmap[viewDoc.owner]?.name || '—' }} ·
            更新于 {{ viewDoc.updated }} · {{ charCount(viewDoc.content) }} 字 · Markdown
          </div>
        </div>
        <div class="page-right">
          <button class="ghost-btn" :disabled="!!exporting"
            title="下载 Markdown 原文；正文含图片时与图片一起打包为 zip"
            @click="downloadMd">
            {{ exporting === 'download' ? '打包中…' : '下载 .md' }}
          </button>
          <button class="ghost-btn" :disabled="!!exporting"
            title="生成 PDF 文件（阅读器侧边大纲按标题层级生成）"
            @click="exportPdf">
            {{ exporting === 'pdf' ? '生成中…' : '导出 PDF' }}
          </button>
          <button v-if="canManage" class="ghost-btn del-text" @click="confirmDel = true">删除</button>
          <button v-if="canWrite" class="btn" @click="startEdit(viewDoc)">编辑</button>
        </div>
      </div>
      <div v-if="exportMsg" class="export-msg rise d1">{{ exportMsg }}</div>

      <div class="card view-card rise d2">
        <aside v-if="toc.length" class="view-toc">
          <div class="toc-title">目录</div>
          <span v-for="t in toc" :key="t.id" class="toc-item" :class="'lv' + t.level"
            @click="jumpTo(t.id)">{{ t.text }}</span>
        </aside>
        <div class="view-main">
          <AppMdPreview :markdown="viewDoc.content" @headings="toc = $event" />
        </div>
      </div>
    </template>

    <!-- ===== 列表视图 ===== -->
    <template v-else>
      <div class="page-head rise d1">
        <div>
          <h1>文档管理</h1>
          <div class="page-meta">以 Markdown 撰写与沉淀团队文档，支持所见即所得编辑与预览</div>
        </div>
        <div class="page-right">
          <input ref="uploadInput" class="up-input" type="file" accept=".md,.markdown" @change="onFilePick">
          <button v-if="canWrite" class="ghost-btn" @click="pickUpload">上传文档</button>
          <PageCreateButton type="document" />
        </div>
      </div>

      <!-- 筛选栏 -->
      <div class="filter-bar rise d2">
        <div class="filter-item">
          <span class="filter-label">分类</span>
          <select class="filter-select" v-model="fCat">
            <option value="all">全部</option>
            <option v-for="c in categories" :key="c">{{ c }}</option>
          </select>
          <button v-if="canWrite" class="op-btn" @click="openCatMgr">管理分类</button>
        </div>
        <div class="filter-item">
          <span class="filter-label">更新人</span>
          <select class="filter-select" v-model="fOwner">
            <option value="">全部</option>
            <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
          </select>
        </div>
        <div class="filter-item">
          <span class="filter-label">关键字</span>
          <input class="field-input f-search" v-model="fKw" placeholder="搜索标题或正文…">
        </div>
      </div>

      <!-- 文档列表 -->
      <div class="card table-card rise d3">
        <div class="table-head">
          <div class="col-title">文档标题</div>
          <div class="col-cat">分类</div>
          <div class="col-owner">更新人</div>
          <div class="col-time">更新时间</div>
          <div class="col-ops">操作</div>
        </div>

        <div v-for="d in paged" :key="d.id" class="table-row clickable" @click="openView(d)">
          <div class="col-title name-cell">{{ d.title }}<span class="doc-id">{{ d.id }}</span></div>
          <div class="col-cat"><span class="cat-chip">{{ d.category }}</span></div>
          <div class="col-owner">
            <div class="owner-cell">
              <span class="mini-ava ava" :class="mmap[d.owner]?.color">{{ mmap[d.owner]?.short }}</span>{{ mmap[d.owner]?.name || '—' }}
            </div>
          </div>
          <div class="col-time date-cell">{{ d.updated }}</div>
          <div class="col-ops" @click.stop>
            <button class="op-btn" @click="openView(d)">查看</button>
            <button v-if="canWrite" class="op-btn" @click="startEdit(d)">编辑</button>
            <button v-if="canManage" class="op-btn danger" @click="viewId = d.id; confirmDel = true">删除</button>
          </div>
        </div>

        <div v-if="!rows.length" class="empty">没有符合条件的文档</div>
        <!-- 分页（全站统一组件，total 取后端筛选后的总条数） -->
        <AppPager v-else :total="total" :page="page" :page-size="pageSize"
          @update:page="page = $event" @update:pageSize="pageSize = $event" />
      </div>
    </template>

    <!-- 分类管理弹窗（560 档） -->
    <AppModal v-if="catMgrOpen" title="管理文档分类" sub="新增、重命名或删除文档分类" width="560"
      @close="catMgrOpen = false">
      <div>
        <div class="mgr-add">
          <input class="field-input mgr-input" v-model="mgrName" maxlength="12" placeholder="输入新分类名称"
            @keydown.enter="mgrAdd" @input="mgrErr = ''">
          <button class="btn" @click="mgrAdd">添加</button>
        </div>
        <div v-if="mgrErr" class="field-error" style="margin-bottom:10px">{{ mgrErr }}</div>
        <div class="mgr-list">
          <div v-for="c in categories" :key="c" class="mgr-row">
            <template v-if="renaming === c">
              <input class="field-input mgr-input" :ref="el => (renameInput = el)" v-model="renameVal"
                maxlength="12" @input="mgrErr = ''" @keydown.enter="mgrRenameConfirm"
                @keydown.esc="renaming = null">
              <button class="op-btn" @click="mgrRenameConfirm">确定</button>
              <button class="op-btn" @click="renaming = null">取消</button>
            </template>
            <template v-else>
              <span class="mgr-name">{{ c }}</span>
              <span class="mgr-count">{{ docCount(c) }} 篇文档</span>
              <button class="op-btn" @click="startRename(c)">重命名</button>
              <button class="op-btn danger" @click="mgrDelete(c)">删除</button>
            </template>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="ghost-btn" @click="catMgrOpen = false">关闭</button>
      </template>
    </AppModal>

    <!-- 上传 Markdown 文档 -->
    <AppModal v-if="uploadOpen" title="上传 Markdown 文档" sub="读取本地 .md 文件并保存为一篇新文档" width="560"
      @close="uploadOpen = false">
      <div>
        <div v-if="uploadFile" class="up-file">
          <span class="up-file-ico">
            <svg viewBox="0 0 24 24">
              <path d="M6 4.5A1.5 1.5 0 0 1 7.5 3h6L18 7.5V19.5A1.5 1.5 0 0 1 16.5 21h-9A1.5 1.5 0 0 1 6 19.5z" />
              <path d="M13.5 3v4.5H18" />
              <path d="M9 12.5h6M9 16h4" />
            </svg>
          </span>
          <div class="up-file-meta">
            <div class="up-file-name">{{ uploadFile.name }}</div>
            <div class="up-file-sub">{{ fmtSize(uploadFile.size) }} · {{ charCount(uploadFile.content) }} 字</div>
          </div>
        </div>
        <div class="field">
          <label class="field-label">所属分类<span class="req">*</span></label>
          <select class="field-select" v-model="uploadCat" :disabled="!uploadFile">
            <option v-if="!categories.length" value="">暂无分类</option>
            <option v-for="c in categories" :key="c">{{ c }}</option>
          </select>
        </div>
        <div v-if="uploadErr" class="field-error">{{ uploadErr }}</div>
      </div>
      <template #footer>
        <button class="ghost-btn" @click="uploadOpen = false">取消</button>
        <button class="btn" :disabled="!canUpload" @click="confirmUpload">{{ uploading ? '上传中…' : '上传' }}</button>
      </template>
    </AppModal>

    <!-- 删除确认（列表行 / 查看页共用） -->
    <AppConfirm v-if="confirmDel && viewDoc" title="删除文档"
      :text="`确定删除文档「${viewDoc.title}」？删除后不可恢复。`"
      @confirm="doDelete" @close="confirmDel = false" />

    <!-- 未保存修改离开确认 -->
    <AppConfirm v-if="confirmDiscard" title="放弃修改"
      text="当前文档有未保存的修改，离开后这些内容会丢失。"
      confirm-text="放弃修改"
      @confirm="confirmDiscard = false; backToList()" @close="confirmDiscard = false" />
  </template>
</template>

<style scoped>
/* ===== 编辑视图 ===== */
.edit-card{padding:20px 24px 24px}
.doc-title{width:100%;border:none;outline:none;background:none;font-family:inherit;
  font-size:20px;font-weight:700;color:var(--t1);padding:0 0 12px}
.doc-title::placeholder{color:var(--t3);font-weight:400}
.edit-meta{display:flex;align-items:center;gap:8px;margin-bottom:12px}
.meta-tip{margin-left:auto;font-size:12px;color:var(--t3)}

/* ===== 查看视图（全页阅读 + 目录） ===== */
.back-btn{display:inline-flex;align-items:center;gap:3px;height:28px;padding:0 10px 0 6px;margin-bottom:8px;
  border:1px solid var(--border);border-radius:var(--radius-sm);background:var(--card);color:var(--t2);
  font-size:13px;font-family:inherit;cursor:pointer;box-shadow:var(--shadow-xs);
  transition:border-color .15s var(--ease),color .15s var(--ease),background .15s var(--ease)}
.back-btn:hover{border-color:var(--border-strong);color:var(--t1);background:var(--surface-3)}
.back-btn svg{width:14px;height:14px;stroke:currentColor;stroke-width:2;fill:none;stroke-linecap:round;stroke-linejoin:round}
/* 阅读页整页铺满后正文行宽会过长，卡片单独限宽居中；左右留白按阅读页放大 */
.view-card{display:flex;gap:44px;align-items:flex-start;padding:30px 44px;max-width:1200px;margin:0 auto}
.view-main{flex:1;min-width:0;font-size:15px;line-height:1.78;color:var(--t1)}
.view-main :deep(h1),
.view-main :deep(h2),
.view-main :deep(h3),
.view-main :deep(h4),
.view-main :deep(h5),
.view-main :deep(h6){scroll-margin-top:72px}
.view-main :deep(p){font-size:15px}
.view-main :deep(li){font-size:15px}
.export-msg{font-size:12px;color:var(--t3);margin:-6px 0 12px}
.view-toc{width:210px;flex:none;position:sticky;top:68px}
.toc-title{font-size:12px;color:var(--t3);letter-spacing:1px;margin-bottom:10px}
.toc-item{display:block;font-size:13px;line-height:1.55;color:var(--t2);padding:3px 0;
  cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.toc-item.lv1{font-weight:700;color:var(--t1);margin-top:8px}
.toc-item.lv2{padding-left:12px}
.toc-item.lv3,
.toc-item.lv4{padding-left:24px;color:var(--t3)}
.del-text{color:var(--red)}

/* ===== 筛选 ===== */
.f-search{width:240px;height:30px;font-size:13px}

/* ===== 分类管理弹窗（内容区复用 modal-main 内边距） ===== */
.mgr-add{display:flex;gap:8px;margin-bottom:12px}
.mgr-input{flex:1;min-width:0;height:32px;font-size:13px}
.mgr-add .btn{height:32px;padding:0 16px;flex:none}
.mgr-row{display:flex;align-items:center;gap:10px;padding:11px 0;border-bottom:1px solid var(--track)}
.mgr-row:last-child{border-bottom:none}
.mgr-name{font-size:14px;color:var(--t1);font-weight:400}
.mgr-count{font-size:12px;color:var(--t3);margin-right:auto}
.mgr-row .op-btn.danger{color:var(--red)}

/* ===== 上传 Markdown ===== */
.up-input{display:none}
.up-file{display:flex;align-items:center;gap:12px;padding:12px 14px;margin-bottom:14px;
  border:1px solid var(--border);border-radius:10px;background:var(--surface-2)}
.up-file-ico{width:34px;height:34px;flex:none;border-radius:9px;background:var(--primary-bg);
  display:flex;align-items:center;justify-content:center}
.up-file-ico svg{width:17px;height:17px;stroke:var(--primary);stroke-width:1.7;fill:none;stroke-linecap:round;stroke-linejoin:round}
.up-file-name{font-size:14px;color:var(--t1);word-break:break-all}
.up-file-sub{font-size:12px;color:var(--t3);margin-top:3px}

/* ===== 列表 ===== */
.col-title{flex:1;min-width:220px}
.col-cat{width:100px;flex:none}
.col-owner{width:110px;flex:none}
.col-time{width:150px;flex:none;font-variant-numeric:tabular-nums}
.col-ops{width:150px;flex:none;text-align:right}

.name-cell{font-size:14px;color:var(--t1);display:flex;align-items:baseline;gap:8px}
.doc-id{font-size:12px;color:var(--t3);font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.cat-chip{font-size:12px;padding:2px 8px;border-radius:6px;background:var(--track);
  color:var(--t2);font-weight:400}
.owner-cell{display:flex;align-items:center;gap:6px;font-size:13px;color:var(--t2)}
.date-cell{font-size:13px;color:var(--t3);font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.op-btn{border:none;background:none;font-size:13px;color:var(--primary);
  cursor:pointer;font-family:inherit;padding:0 4px}
.op-btn.danger{color:var(--red)}
.empty{padding:24px;text-align:center;font-size:13px;color:var(--t3)}

@media (max-width:1150px){
  .table-head,.table-row{flex-wrap:wrap;gap:8px}
  .col-title{flex-basis:100%}
  .col-cat,.col-owner,.col-time{width:auto;text-align:left}
  .view-card{flex-direction:column;gap:14px;padding:22px 20px}
  /* 窄屏目录放正文下方，避免长目录挡在文章前面 */
  .view-main{order:1}
  .view-toc{order:2;width:100%;position:static}
}
</style>
