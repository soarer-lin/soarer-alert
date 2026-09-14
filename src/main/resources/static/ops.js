(function () {
    'use strict';

    const API_BASE = `${window.location.origin}/api`;
    const DOCUMENT_UPLOAD_EXTENSIONS = ['.txt', '.md', '.markdown', '.pdf', '.doc', '.docx'];
    const DOCUMENT_MAX_SIZE = 50 * 1024 * 1024;
    const state = {
        view: 'overview',
        statusFilter: '',
        runs: [],
        selectedRunId: null,
        detail: null,
        detailTab: 'summary',
        alerts: [],
        documents: []
    };

    let socket = null;
    let polling = false;
    let toastTimer = null;

    function refreshIcons() {
        if (window.lucide && typeof window.lucide.createIcons === 'function') {
            window.lucide.createIcons();
        }
    }

    function stripEmoji(value) {
        return String(value ?? '')
            .replace(/\p{Extended_Pictographic}(?:\uFE0F|\u200D\p{Extended_Pictographic})*/gu, '')
            .replace(/[\u{1F3FB}-\u{1F3FF}\u{E0020}-\u{E007F}]/gu, '');
    }

    const elements = {
        connectionPill: document.getElementById('connectionPill'),
        refreshButton: document.getElementById('refreshButton'),
        metrics: {
            totalRuns: document.getElementById('totalRuns'),
            queuedRuns: document.getElementById('queuedRuns'),
            runningRuns: document.getElementById('runningRuns'),
            retryingRuns: document.getElementById('retryingRuns'),
            successfulRuns: document.getElementById('successfulRuns'),
            failedRuns: document.getElementById('failedRuns'),
            successfulReports: document.getElementById('successfulReports'),
            alertsCount: document.getElementById('alertsCount'),
            documentsCount: document.getElementById('documentsCount')
        },
        recentRunsBody: document.getElementById('recentRunsBody'),
        statusFilter: document.getElementById('statusFilter'),
        newRunInput: document.getElementById('newRunInput'),
        createRunButton: document.getElementById('createRunButton'),
        runCount: document.getElementById('runCount'),
        runsBody: document.getElementById('runsBody'),
        selectedRunTitle: document.getElementById('selectedRunTitle'),
        detailContent: document.getElementById('detailContent'),
        alertsBody: document.getElementById('alertsBody'),
        alertCount: document.getElementById('alertCount'),
        documentsBody: document.getElementById('documentsBody'),
        documentUploadButton: document.getElementById('documentUploadButton'),
        documentUploadInput: document.getElementById('documentUploadInput'),
        toast: document.getElementById('toast')
    };

    async function request(path, options) {
        const response = await fetch(`${API_BASE}${path}`, {
            headers: {'Content-Type': 'application/json'},
            ...options
        });
        const payload = await response.json().catch(() => null);
        if (!response.ok || !payload || payload.code !== 200) {
            const message = payload && payload.message ? payload.message : `HTTP ${response.status}`;
            throw new Error(message);
        }
        return payload.data;
    }

    async function loadOverview() {
        const overview = await request('/ai_ops/overview');
        elements.metrics.totalRuns.textContent = formatCount(overview.totalRuns);
        elements.metrics.queuedRuns.textContent = formatCount(overview.queuedRuns);
        elements.metrics.runningRuns.textContent = formatCount(overview.runningRuns);
        elements.metrics.retryingRuns.textContent = formatCount(overview.retryingRuns);
        elements.metrics.successfulRuns.textContent = formatCount(overview.successfulRuns);
        elements.metrics.failedRuns.textContent = formatCount(overview.failedRuns);
        elements.metrics.successfulReports.textContent = formatCount(overview.successfulReports);
        elements.metrics.alertsCount.textContent = formatCount(overview.alerts);
        elements.metrics.documentsCount.textContent = formatCount(overview.documents);
    }

    async function loadRuns(selectFirst = true) {
        const query = new URLSearchParams({page: '0', size: '50'});
        if (state.statusFilter) {
            query.set('status', state.statusFilter);
        }
        const page = await request(`/ai_ops/runs?${query.toString()}`);
        state.runs = page.content || [];
        elements.runCount.textContent = formatCount(page.totalElements);
        if (selectFirst && !state.selectedRunId && state.runs.length > 0) {
            state.selectedRunId = state.runs[0].id;
        }
        renderRuns();
        renderRecentRuns();
    }

    async function loadDetail() {
        if (!state.selectedRunId) {
            state.detail = null;
            renderDetail();
            return;
        }
        state.detail = await request(`/ai_ops/runs/${state.selectedRunId}`);
        renderDetail();
    }

    async function loadAlerts() {
        const page = await request('/ai_ops/alerts?page=0&size=100');
        state.alerts = page.content || [];
        elements.alertCount.textContent = formatCount(page.totalElements);
        renderAlerts();
    }

    async function loadDocuments() {
        const page = await request('/documents?page=0&size=100');
        state.documents = page.content || [];
        renderDocuments();
    }

    async function refreshAll() {
        elements.refreshButton.disabled = true;
        try {
            await Promise.all([loadOverview(), loadRuns(), loadAlerts(), loadDocuments()]);
            await loadDetail();
            showToast('数据已刷新');
        } catch (error) {
            showToast(error.message, true);
        } finally {
            elements.refreshButton.disabled = false;
        }
    }

    async function createRun() {
        const requestText = elements.newRunInput.value.trim();
        elements.createRunButton.disabled = true;
        try {
            const run = await request('/ai_ops/runs', {
                method: 'POST',
                body: JSON.stringify({requestText: requestText || null})
            });
            state.selectedRunId = run.id;
            state.detailTab = 'summary';
            elements.newRunInput.value = '';
            setView('diagnosis');
            await loadRuns(false);
            await loadDetail();
            await loadOverview();
            showToast(`诊断任务已创建：${run.id}`);
        } catch (error) {
            showToast(error.message, true);
        } finally {
            elements.createRunButton.disabled = false;
        }
    }

    async function cancelRun() {
        if (!state.selectedRunId) {
            showToast('请先选择诊断任务', true);
            return;
        }
        try {
            const result = await request(`/ai_ops/runs/${state.selectedRunId}/cancel`, {method: 'POST'});
            showToast(result.message || `取消结果：${result.result}`);
            await Promise.all([loadRuns(false), loadDetail(), loadOverview()]);
        } catch (error) {
            showToast(error.message, true);
        }
    }

    function sendControl(command) {
        if (!state.selectedRunId) {
            showToast('请先选择诊断任务', true);
            return;
        }
        if (command === 'cancel') {
            cancelRun();
            return;
        }
        if (command === 'status') {
            loadDetail().catch(error => showToast(error.message, true));
            return;
        }
        if (!socket || socket.readyState !== WebSocket.OPEN) {
            showToast('WebSocket 未连接，暂停/恢复暂不可用', true);
            return;
        }
        socket.send(JSON.stringify({command, runId: state.selectedRunId}));
    }

    function connectWebSocket() {
        try {
            socket = new WebSocket(window.SuperBizAuth.websocketUrl('/ws/diagnosis'));
        } catch (error) {
            setConnection(false);
            window.setTimeout(connectWebSocket, 5000);
            return;
        }

        socket.addEventListener('open', () => setConnection(true));
        socket.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            handleWebSocketMessage(message);
        });
        socket.addEventListener('close', () => {
            setConnection(false);
            window.setTimeout(connectWebSocket, 5000);
        });
        socket.addEventListener('error', () => setConnection(false));
    }

    function handleWebSocketMessage(message) {
        if (message.type === 'ready') {
            setConnection(true);
            return;
        }
        if (message.error) {
            showToast(message.message || message.error, true);
            return;
        }
        if (message.command) {
            const suffix = message.message ? `：${message.message}` : '';
            showToast(`${message.command} ${message.result}${suffix}`, message.result !== 'ACCEPTED');
            if (message.result === 'ACCEPTED') {
                Promise.all([loadRuns(false), loadDetail(), loadOverview()])
                    .catch(error => showToast(error.message, true));
            }
            return;
        }
        if (message.runId && message.status) {
            const run = state.runs.find(item => item.id === message.runId);
            if (run) {
                run.status = message.status;
            }
            if (state.selectedRunId === message.runId) {
                loadDetail().catch(() => undefined);
            }
            renderRuns();
            renderRecentRuns();
        }
    }

    function setConnection(online) {
        elements.connectionPill.classList.toggle('online', online);
        elements.connectionPill.classList.toggle('offline', !online);
        elements.connectionPill.textContent = '';
        const dot = document.createElement('span');
        dot.className = 'connection-dot';
        elements.connectionPill.appendChild(dot);
        elements.connectionPill.appendChild(document.createTextNode(online ? '在线' : '离线'));
    }

    function setView(view) {
        state.view = view;
        document.querySelectorAll('.ops-nav-button').forEach(button => {
            button.classList.toggle('active', button.dataset.view === view);
        });
        document.querySelectorAll('.ops-view').forEach(section => {
            section.classList.toggle('active', section.id === `${view}View`);
        });
    }

    function setDetailTab(tab) {
        state.detailTab = tab;
        document.querySelectorAll('.detail-tabs button').forEach(button => {
            button.classList.toggle('active', button.dataset.tab === tab);
        });
        renderDetail();
    }

    function renderRecentRuns() {
        elements.recentRunsBody.innerHTML = state.runs.slice(0, 8).map(run => `
            <tr>
                <td>${statusChip(run.status)}</td>
                <td>${escapeHtml(formatDateTime(run.startedAt))}</td>
                <td>${escapeHtml(formatDuration(run.durationMs))}</td>
                <td class="cell-text">${escapeHtml(stripEmoji(run.requestText || ''))}</td>
            </tr>
        `).join('') || emptyRow(4, '暂无诊断任务');
    }

    function renderRuns() {
        elements.runsBody.innerHTML = state.runs.map(run => `
            <tr data-run-id="${escapeHtml(run.id)}" class="${run.id === state.selectedRunId ? 'selected' : ''}">
                <td>${statusChip(run.status)}</td>
                <td>${escapeHtml(formatDateTime(run.startedAt))}</td>
                <td class="cell-text">${escapeHtml(stripEmoji(run.requestText || ''))}</td>
            </tr>
        `).join('') || emptyRow(3, '当前筛选没有任务');
    }

    function renderDetail() {
        if (!state.detail) {
            elements.selectedRunTitle.textContent = '未选择任务';
            elements.detailContent.innerHTML = '<div class="empty-state">暂无任务详情</div>';
            return;
        }

        const run = state.detail.run;
        elements.selectedRunTitle.textContent = stripEmoji(`${run.id} · ${run.requestText || ''}`);
        if (state.detailTab === 'summary') {
            renderSummary(run);
        } else if (state.detailTab === 'timeline') {
            renderTimeline(state.detail.steps || []);
        } else if (state.detailTab === 'tools') {
            renderTools(state.detail.toolInvocations || []);
        } else if (state.detailTab === 'evidence') {
            renderEvidence(state.detail.evidence || []);
        } else if (state.detailTab === 'report') {
            renderReport(state.detail.report);
        }
    }

    function renderSummary(run) {
        elements.detailContent.innerHTML = `
            <div class="definition-grid">
                ${definitionItem('任务 ID', run.id)}
                ${definitionItem('状态', run.status)}
                ${definitionItem('开始时间', formatDateTime(run.startedAt))}
                ${definitionItem('完成时间', formatDateTime(run.completedAt))}
                ${definitionItem('耗时', formatDuration(run.durationMs))}
                ${definitionItem('请求', run.requestText || '-')}
                ${definitionItem('错误信息', run.errorMessage || '-')}
            </div>
        `;
    }

    function renderTimeline(steps) {
        if (!steps.length) {
            elements.detailContent.innerHTML = '<div class="empty-state">暂无 Agent 步骤</div>';
            return;
        }
        elements.detailContent.innerHTML = `<div class="timeline">${steps.map(step => `
            <article class="timeline-item ${step.status === 'FAILED' ? 'failed' : ''}">
                <div class="timeline-marker"></div>
                <div class="timeline-body">
                    <div class="timeline-title">
                        <strong>#${step.stepIndex} ${escapeHtml(stripEmoji(step.agentName))}</strong>
                        ${statusChip(step.status)}
                    </div>
                    <div class="text-muted">${escapeHtml(formatDateTime(step.startedAt))} · ${escapeHtml(formatDuration(step.durationMs))}</div>
                    ${step.instruction ? `<div class="timeline-text">${renderTimelineText(step.instruction)}</div>` : ''}
                    ${step.outputText ? `<div class="timeline-text">${renderTimelineText(step.outputText)}</div>` : ''}
                    ${step.errorMessage ? `<div class="timeline-text">${renderTimelineText(step.errorMessage)}</div>` : ''}
                </div>
            </article>
        `).join('')}</div>`;
    }

    function renderTools(invocations) {
        if (!invocations.length) {
            elements.detailContent.innerHTML = '<div class="empty-state">暂无工具调用</div>';
            return;
        }
        elements.detailContent.innerHTML = `
            <div class="table-container">
                <table>
                    <thead><tr><th>工具</th><th>状态</th><th>开始时间</th><th>耗时</th><th>错误</th></tr></thead>
                    <tbody>${invocations.map(item => `
                        <tr>
                            <td>${escapeHtml(stripEmoji(item.toolName))}</td>
                            <td>${statusChip(item.status)}</td>
                            <td>${escapeHtml(formatDateTime(item.startedAt))}</td>
                            <td>${escapeHtml(formatDuration(item.durationMs))}</td>
                            <td class="cell-text">${escapeHtml(stripEmoji(item.errorMessage || '-'))}</td>
                        </tr>
                    `).join('')}</tbody>
                </table>
            </div>
        `;
    }

    function renderEvidence(evidence) {
        if (!evidence.length) {
            elements.detailContent.innerHTML = '<div class="empty-state">暂无证据</div>';
            return;
        }
        elements.detailContent.innerHTML = evidence.map(item => `
            <article class="definition-item" style="margin-bottom: 12px;">
                <span>${escapeHtml(stripEmoji(item.evidenceType))} · ${escapeHtml(formatDateTime(item.createdAt))}</span>
                <strong>${escapeHtml(stripEmoji(item.source || '-'))}</strong>
                <div class="timeline-text">${renderTimelineText(item.content || '')}</div>
            </article>
        `).join('');
    }

    function renderReport(report) {
        if (!report) {
            elements.detailContent.innerHTML = '<div class="empty-state">报告尚未生成</div>';
            return;
        }
        elements.detailContent.innerHTML = renderReportContent(report.content || '');
    }

    function normalizeMarkdownText(value) {
        return String(value ?? '')
            .replace(/\\([#*_`>|~-])/g, '$1')
            .replace(/```[a-zA-Z0-9_-]*\n?/g, '')
            .replace(/```/g, '')
            .replace(/^#{1,6}\s+/gm, '')
            .replace(/\*\*([^*]+)\*\*/g, '$1')
            .replace(/\*([^*]+)\*/g, '$1')
            .replace(/__([^_]+)__/g, '$1')
            .replace(/`([^`]+)`/g, '$1')
            .replace(/^\s*[-*+]\s+/gm, '')
            .replace(/^\s*>\s?/gm, '')
            .replace(/^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/gm, '')
            .replace(/^\s*\|?[-: |]+\|[-: |]*\s*$/gm, '')
            .replace(/\s*\|\s*/g, ' · ')
            .replace(/\n{3,}/g, '\n\n')
            .trim();
    }

    function renderTimelineText(value) {
        const content = String(value ?? '').replace(/\\([#*_`>|~-])/g, '$1');
        if (!content.trim()) {
            return '';
        }

        if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') {
            return escapeHtml(normalizeMarkdownText(content)).replace(/\n/g, '<br>');
        }

        try {
            marked.setOptions({ gfm: true, breaks: true });
            const html = marked.parse(content);
            return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
        } catch (error) {
            console.error('时间线 Markdown 渲染失败:', error);
            return escapeHtml(normalizeMarkdownText(content)).replace(/\n/g, '<br>');
        }
    }

    function renderReportContent(content) {
        const reportContent = String(content ?? '');
        if (!reportContent.trim()) {
            return '<div class="empty-state">报告内容为空</div>';
        }

        if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') {
            return `<div class="report-content report-plain">${escapeHtml(normalizeMarkdownText(reportContent))}</div>`;
        }

        try {
            marked.setOptions({ gfm: true, breaks: true });
            const html = marked.parse(reportContent.replace(/\\([#*_`>|~-])/g, '$1'));
            return `<div class="report-content">${DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })}</div>`;
        } catch (error) {
            console.error('报告 Markdown 渲染失败:', error);
            return `<div class="report-content report-plain">${escapeHtml(normalizeMarkdownText(reportContent))}</div>`;
        }
    }

    function renderAlerts() {
        elements.alertsBody.innerHTML = state.alerts.map(alert => `
            <tr>
                <td>${escapeHtml(stripEmoji(alert.severity || '-'))}</td>
                <td>${escapeHtml(stripEmoji(alert.alertName))}</td>
                <td>${escapeHtml(stripEmoji(alert.serviceName || '-'))}</td>
                <td>${escapeHtml(stripEmoji(alert.environment || '-'))}</td>
                <td>${statusChip(alert.status || 'UNKNOWN')}</td>
                <td>${escapeHtml(formatDateTime(alert.lastTriggeredAt))}</td>
            </tr>
        `).join('') || emptyRow(6, '暂无告警');
    }

    function renderDocuments() {
        elements.documentsBody.innerHTML = state.documents.map(doc => `
            <tr>
                <td class="cell-text">${escapeHtml(stripEmoji(doc.fileName))}</td>
                <td>${escapeHtml(stripEmoji(doc.mediaType || '-'))}</td>
                <td>${escapeHtml(formatBytes(doc.byteSize))}</td>
                <td>${statusChip(doc.status || 'UNKNOWN')}</td>
                <td>${escapeHtml(formatDateTime(doc.indexedAt))}</td>
                <td>${escapeHtml(formatDateTime(doc.createdAt))}</td>
                <td>
                    <div class="document-actions">
                        <button class="ops-button compact" type="button" data-view-document="${doc.id}" title="查看文档原文">
                            <i data-lucide="eye"></i><span>查看</span>
                        </button>
                        ${doc.canDelete
                            ? `<button class="ops-button compact danger" type="button" data-delete-document="${doc.id}" data-file-name="${escapeHtml(doc.fileName)}" title="删除文档">
                                <i data-lucide="trash-2"></i><span>删除</span>
                            </button>`
                            : `<button class="ops-button compact" type="button" disabled title="仅文档上传者和管理员可以删除">
                                <i data-lucide="lock"></i><span>不可删除</span>
                            </button>`}
                    </div>
                </td>
            </tr>
        `).join('') || emptyRow(7, '暂无文档');
        refreshIcons();
    }

    async function deleteDocument(button) {
        const documentId = button.dataset.deleteDocument;
        const documentRow = state.documents.find(item => item.id === documentId);
        if (!documentRow || !documentRow.canDelete) {
            showToast('无权删除该文档', true);
            return;
        }
        if (!window.confirm(`确定删除文档“${documentRow.fileName}”吗？删除后知识库索引将一并移除。`)) {
            return;
        }

        button.disabled = true;
        try {
            await request(`/documents/${documentId}`, {method: 'DELETE'});
            await Promise.all([loadDocuments(), loadOverview()]);
            showToast('文档已删除');
        } catch (error) {
            showToast(error.message, true);
            button.disabled = false;
        }
    }

    function isAllowedDocumentFile(file) {
        const fileName = file.name.toLowerCase();
        return DOCUMENT_UPLOAD_EXTENSIONS.some(extension => fileName.endsWith(extension));
    }

    async function uploadDocument(file) {
        if (!isAllowedDocumentFile(file)) {
            showToast('只支持上传 TXT、Markdown、PDF、DOC 和 DOCX 格式的文件', true);
            return;
        }
        if (file.size > DOCUMENT_MAX_SIZE) {
            showToast('文件大小不能超过50MB', true);
            return;
        }

        const buttonLabel = elements.documentUploadButton.querySelector('span');
        const originalLabel = buttonLabel.textContent;
        elements.documentUploadButton.disabled = true;
        buttonLabel.textContent = '上传中';
        try {
            const formData = new FormData();
            formData.append('file', file);
            const response = await fetch(`${API_BASE}/upload`, {
                method: 'POST',
                body: formData
            });
            const payload = await response.json().catch(() => null);
            if (!response.ok || !payload || payload.code !== 200 || !payload.data) {
                throw new Error(payload && payload.message ? payload.message : `HTTP ${response.status}`);
            }

            const result = payload.data;
            showToast(result.duplicate
                ? `${file.name} 已存在于知识库，系统跳过重复索引`
                : `${file.name} 上传成功，索引任务已加入队列`);
            await Promise.all([loadDocuments(), loadOverview()]);
        } catch (error) {
            showToast(
                error.message === '额度已耗尽，请联系管理员重置~'
                    ? error.message
                    : `文件上传失败：${error.message}`,
                true
            );
        } finally {
            elements.documentUploadInput.value = '';
            buttonLabel.textContent = originalLabel;
            elements.documentUploadButton.disabled = false;
        }
    }

    function definitionItem(label, value) {
        return `<div class="definition-item"><span>${escapeHtml(stripEmoji(label))}</span><strong>${escapeHtml(stripEmoji(value || '-'))}</strong></div>`;
    }

    function statusChip(status) {
        const safeStatus = status || 'UNKNOWN';
        const cleanStatus = stripEmoji(safeStatus);
        return `<span class="status-chip status-${escapeHtml(cleanStatus)}">${escapeHtml(cleanStatus)}</span>`;
    }

    function emptyRow(columnCount, message) {
        return `<tr><td class="text-muted" colspan="${columnCount}">${escapeHtml(stripEmoji(message))}</td></tr>`;
    }

    function showToast(message, isError = false) {
        window.clearTimeout(toastTimer);
        elements.toast.textContent = stripEmoji(message);
        elements.toast.classList.toggle('error', isError);
        elements.toast.classList.add('visible');
        toastTimer = window.setTimeout(() => elements.toast.classList.remove('visible'), 3600);
    }

    function formatCount(value) {
        return Number(value || 0).toLocaleString('zh-CN');
    }

    function formatDateTime(value) {
        if (!value) {
            return '-';
        }
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', {hour12: false});
    }

    function formatDuration(value) {
        if (value === null || value === undefined) {
            return '-';
        }
        if (value < 1000) {
            return `${value}ms`;
        }
        if (value < 60000) {
            return `${(value / 1000).toFixed(1)}s`;
        }
        const minutes = Math.floor(value / 60000);
        const seconds = Math.round((value % 60000) / 1000);
        return `${minutes}m ${seconds}s`;
    }

    function formatBytes(value) {
        if (value === null || value === undefined) {
            return '-';
        }
        if (value < 1024) {
            return `${value}B`;
        }
        if (value < 1024 * 1024) {
            return `${(value / 1024).toFixed(1)}KB`;
        }
        return `${(value / 1024 / 1024).toFixed(1)}MB`;
    }

    function escapeHtml(value) {
        return String(value ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    document.querySelectorAll('.ops-nav-button').forEach(button => {
        button.addEventListener('click', () => setView(button.dataset.view));
    });

    document.querySelectorAll('[data-goto]').forEach(button => {
        button.addEventListener('click', () => setView(button.dataset.goto));
    });

    document.querySelectorAll('.detail-tabs button').forEach(button => {
        button.addEventListener('click', () => setDetailTab(button.dataset.tab));
    });

    document.querySelectorAll('[data-control]').forEach(button => {
        button.addEventListener('click', () => sendControl(button.dataset.control));
    });

    elements.refreshButton.addEventListener('click', refreshAll);
    elements.createRunButton.addEventListener('click', createRun);
    elements.documentUploadButton.addEventListener('click', () => elements.documentUploadInput.click());
    elements.documentUploadInput.addEventListener('change', event => {
        const file = event.target.files[0];
        if (file) {
            uploadDocument(file);
        }
    });
    elements.newRunInput.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            createRun();
        }
    });
    elements.statusFilter.addEventListener('change', async event => {
        state.statusFilter = event.target.value;
        try {
            await loadRuns();
            await loadDetail();
        } catch (error) {
            showToast(error.message, true);
        }
    });
    elements.runsBody.addEventListener('click', event => {
        const row = event.target.closest('tr[data-run-id]');
        if (!row) {
            return;
        }
        state.selectedRunId = row.dataset.runId;
        renderRuns();
        loadDetail().catch(error => showToast(error.message, true));
    });

    elements.documentsBody.addEventListener('click', event => {
        const viewButton = event.target.closest('[data-view-document]');
        const deleteButton = event.target.closest('[data-delete-document]');
        if (viewButton) {
            window.open(`/api/documents/${viewButton.dataset.viewDocument}/content`, '_blank', 'noopener,noreferrer');
            return;
        }
        if (deleteButton) {
            deleteDocument(deleteButton);
        }
    });

    window.setInterval(async () => {
        if (document.hidden || polling) {
            return;
        }
        polling = true;
        try {
            await loadOverview();
            await loadRuns(false);
            if (state.view === 'diagnosis') {
                await loadDetail();
            }
            if (state.view === 'alerts') {
                await loadAlerts();
            }
            if (state.view === 'documents') {
                await loadDocuments();
            }
        } catch (error) {
            // Polling errors are intentionally quiet; manual refresh shows details.
        } finally {
            polling = false;
        }
    }, 5000);

    if (window.lucide) {
        window.lucide.createIcons();
    }
    setConnection(false);
    connectWebSocket();
    refreshAll();
})();
