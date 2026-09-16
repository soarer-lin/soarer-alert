// SoarerAlertAgent 前端应用
class SoarerAlertAgentApp {
    constructor() {
        this.apiBaseUrl = `${window.location.origin}/api`;
        this.currentMode = 'stream'; // 'quick' 或 'stream'
        this.sessionId = this.generateSessionId();
        this.currentChatHistory = []; // 当前对话的消息历史
        this.chatHistories = []; // 当前登录用户的服务端会话列表
        this.isCurrentChatFromHistory = false; // 标记当前对话是否是从历史记录加载的
        this.sessionStreams = new Map(); // 本页面发起的生成任务，按 sessionId 隔离
        this.activeChatTasks = new Map(); // 服务端确认的生成任务，用于跨会话/切页恢复
        this.activeTaskPollTimer = null;
        this.activeTaskRefreshInProgress = false;
        this.chatViewToken = 0; // 使切换对话后仍在路上的历史加载失效
        
        this.initializeElements();
        this.bindEvents();
        this.initInteractiveSurface();
        this.updateUI();
        this.initMarkdown();
        this.checkAndSetCentered();
        this.loadChatHistories();
        this.startActiveTaskPolling();
        this.refreshActiveChatTasks();
        this.renderLucideIcons();
    }

    get isStreaming() {
        return this.isSessionBusy(this.sessionId);
    }

    set isStreaming(value) {
        this.setSessionStreaming(this.sessionId, Boolean(value));
    }

    // 初始化Markdown配置
    initMarkdown() {
        // 等待 marked 库加载完成
        const checkMarked = () => {
            if (typeof marked !== 'undefined') {
                try {
                    // 配置marked选项
                    marked.setOptions({
                        breaks: true,  // 支持GFM换行
                        gfm: true,     // 启用GitHub风格的Markdown
                        headerIds: false,
                        mangle: false
                    });

                    // 配置代码高亮
                    if (typeof hljs !== 'undefined') {
                        marked.setOptions({
                            highlight: function(code, lang) {
                                if (lang && hljs.getLanguage(lang)) {
                                    try {
                                        return hljs.highlight(code, { language: lang }).value;
                                    } catch (err) {
                                        console.error('代码高亮失败:', err);
                                    }
                                }
                                return code;
                            }
                        });
                    }
                    console.log('Markdown 渲染库初始化成功');
                } catch (e) {
                    console.error('Markdown 配置失败:', e);
                }
            } else {
                // 如果 marked 还没加载，等待一段时间后重试
                setTimeout(checkMarked, 100);
            }
        };
        checkMarked();
    }

    // 安全地渲染 Markdown
    renderMarkdown(content) {
        if (!content) return '';

        // 检查 marked 是否可用
        if (typeof marked === 'undefined') {
            console.warn('marked 库未加载，使用纯文本显示');
            return this.escapeHtml(content);
        }

        try {
            const html = marked.parse(content);
            return html;
        } catch (e) {
            console.error('Markdown 渲染失败:', e);
            return this.escapeHtml(content);
        }
    }

    // 流式内容可能停在未闭合的 Markdown 片段，先做临时兼容渲染。
    renderStreamingMarkdown(content) {
        let safeContent = this.neutralizeIncompleteTrailingTable(String(content || ''));
        safeContent = this.closeUnbalancedInlineMarkdown(safeContent);
        return this.renderMarkdown(safeContent);
    }

    neutralizeIncompleteTrailingTable(content) {
        const lines = content.split('\n');
        let tableStart = -1;
        let tableEnd = -1;

        for (let i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith('|')) {
                if (tableStart < 0) tableStart = i;
                tableEnd = i;
            } else if (lines[i].trim() && tableStart >= 0) {
                tableStart = -1;
                tableEnd = -1;
            }
        }

        if (tableStart < 0) return content;
        for (let i = tableEnd + 1; i < lines.length; i++) {
            if (lines[i].trim()) return content;
        }

        const tableLines = lines.slice(tableStart, tableEnd + 1);
        const plainLines = tableLines
            .map(line => {
                const trimmed = line.trim();
                if (trimmed.includes('-') && /^[\s|:-]+$/.test(trimmed)) return '';
                return trimmed
                    .replace(/^\|/, '')
                    .replace(/\|$/, '')
                    .replace(/\s*\|\s*/g, '  ')
                    .trim();
            })
            .filter((line, index, array) => line || (index > 0 && array[index - 1]));

        lines.splice(tableStart, tableLines.length, ...plainLines);
        return lines.join('\n');
    }

    closeUnbalancedInlineMarkdown(content) {
        let safeContent = content;
        const boldCount = (safeContent.match(/\*\*/g) || []).length;
        if (boldCount % 2 === 1) {
            safeContent += '**';
        }

        const hasUnclosedFence = (/^\s*```/m).test(safeContent)
            && ((safeContent.match(/^\s*```/gm) || []).length % 2 === 1);
        if (!hasUnclosedFence) {
            const inlineCodeCount = (safeContent.match(/`/g) || []).length;
            if (inlineCodeCount % 2 === 1) {
                safeContent += '`';
            }
        }
        return safeContent;
    }

    // 系统 UI 不使用表情符号；模型返回内容由各渲染分支自行保留。
    stripEmoji(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/\p{Extended_Pictographic}(?:\uFE0F|\u200D\p{Extended_Pictographic})*/gu, '')
            .replace(/[\u{1F3FB}-\u{1F3FF}\u{E0020}-\u{E007F}]/gu, '');
    }

    renderLucideIcons() {
        if (window.lucide && typeof window.lucide.createIcons === 'function') {
            window.lucide.createIcons();
        }
    }

    getBusyTask(sessionId) {
        return this.sessionStreams.get(sessionId) || this.activeChatTasks.get(sessionId) || null;
    }

    isSessionBusy(sessionId) {
        return this.sessionStreams.has(sessionId) || this.activeChatTasks.has(sessionId);
    }

    setSessionStreaming(sessionId, streaming, task = {}) {
        if (!sessionId) {
            return;
        }
        if (streaming) {
            const current = this.sessionStreams.get(sessionId) || {};
            this.sessionStreams.set(sessionId, {
                question: task.question || current.question || '',
                partialAnswer: task.partialAnswer || current.partialAnswer || '',
                regenerate: Boolean(task.regenerate || current.regenerate),
                startedAt: task.startedAt || current.startedAt || Date.now()
            });
        } else {
            this.sessionStreams.delete(sessionId);
        }
        this.renderChatHistory();
        if (sessionId === this.sessionId) {
            this.updateUI();
        }
    }

    updateSessionStream(sessionId, patch = {}) {
        if (!this.sessionStreams.has(sessionId)) {
            return;
        }
        const task = this.sessionStreams.get(sessionId);
        this.sessionStreams.set(sessionId, { ...task, ...patch });
        this.renderChatHistory();
    }

    startActiveTaskPolling() {
        if (this.activeTaskPollTimer) {
            return;
        }
        this.activeTaskPollTimer = window.setInterval(() => {
            this.refreshActiveChatTasks();
        }, 2000);
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') {
                this.refreshActiveChatTasks();
            }
        });
    }

    async refreshActiveChatTasks() {
        if (this.activeTaskRefreshInProgress) {
            return;
        }
        this.activeTaskRefreshInProgress = true;
        const previousIds = new Set([
            ...this.activeChatTasks.keys(),
            ...this.sessionStreams.keys()
        ]);

        try {
            const response = await fetch(`${this.apiBaseUrl}/chat/tasks/active`, {
                headers: { 'Accept': 'application/json' }
            });
            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }
            const payload = await response.json();
            if (payload.code !== 200) {
                throw new Error(payload.message || '获取生成中的对话失败');
            }

            this.activeChatTasks = new Map((payload.data || []).map(task => [
                task.sessionId,
                {
                    question: task.question || '',
                    partialAnswer: task.partialAnswer || '',
                    regenerate: Boolean(task.regenerate),
                    startedAt: Number(task.startedAt) || Date.now()
                }
            ]));

            this.renderChatHistory();
            this.renderBusyPlaceholder(this.sessionId);

            const activeIds = new Set(this.activeChatTasks.keys());
            const completedIds = Array.from(previousIds).filter(id =>
                !activeIds.has(id) && !this.sessionStreams.has(id)
            );

            if (completedIds.length > 0) {
                await this.loadChatHistories();
                if (completedIds.includes(this.sessionId)) {
                    await this.loadChatHistory(this.sessionId);
                }
            }
        } catch (error) {
            if (error.message && !String(error.message).includes('HTTP错误: 401')) {
                console.warn('获取生成中的对话状态失败:', error);
            }
        } finally {
            this.activeTaskRefreshInProgress = false;
        }
    }

    findBusyPlaceholder(sessionId) {
        if (!this.chatMessages) {
            return null;
        }
        return Array.from(this.chatMessages.querySelectorAll('.message.assistant.session-busy-placeholder'))
            .find(element => element.dataset.sessionId === sessionId) || null;
    }

    renderBusyPlaceholder(sessionId) {
        if (!sessionId || this.sessionId !== sessionId || !this.chatMessages) {
            return null;
        }

        const task = this.getBusyTask(sessionId);
        if (!task) {
            return null;
        }

        let messageElement = this.findBusyPlaceholder(sessionId);
        if (!messageElement) {
            messageElement = this.addLoadingMessage('', true);
        }

        messageElement.dataset.sessionId = sessionId;
        if (task.partialAnswer) {
            messageElement.className = 'message assistant streaming session-busy-placeholder';
            const content = messageElement.querySelector('.message-content');
            if (content) {
                content.className = 'message-content';
                content.innerHTML = this.renderStreamingMarkdown(task.partialAnswer);
                this.highlightCodeBlocks(content);
            }
        } else {
            messageElement.className = 'message assistant thinking chat-thinking session-busy-placeholder';
        }
        this.scrollToBottom();
        return messageElement;
    }

    initInteractiveSurface() {
        const root = document.documentElement;
        let frame = null;

        window.addEventListener('pointermove', event => {
            if (frame) {
                return;
            }
            frame = window.requestAnimationFrame(() => {
                root.style.setProperty('--pointer-x', `${event.clientX}px`);
                root.style.setProperty('--pointer-y', `${event.clientY}px`);
                root.dataset.pointerActive = 'true';
                frame = null;
            });
        }, { passive: true });

        window.addEventListener('pointerleave', () => {
            root.dataset.pointerActive = 'false';
        });
    }

    // 高亮代码块
    highlightCodeBlocks(container) {
        if (typeof hljs !== 'undefined' && container) {
            try {
                container.querySelectorAll('pre code').forEach((block) => {
                    if (!block.classList.contains('hljs')) {
                        hljs.highlightElement(block);
                    }
                });
            } catch (e) {
                console.error('代码高亮失败:', e);
            }
        }
    }

    // 初始化DOM元素
    initializeElements() {
        // 侧边栏元素
        this.sidebar = document.querySelector('.sidebar');
        this.newChatBtn = document.getElementById('newChatBtn');
        this.aiOpsSidebarBtn = document.getElementById('aiOpsSidebarBtn');
        
        // 输入区域元素
        this.messageInput = document.getElementById('messageInput');
        this.sendButton = document.getElementById('sendButton');
        this.toolsBtn = document.getElementById('toolsBtn');
        this.toolsMenu = document.getElementById('toolsMenu');
        this.uploadFileItem = document.getElementById('uploadFileItem');
        this.modeSelectorBtn = document.getElementById('modeSelectorBtn');
        this.modeDropdown = document.getElementById('modeDropdown');
        this.currentModeText = document.getElementById('currentModeText');
        this.fileInput = document.getElementById('fileInput');
        
        // 聊天区域元素
        this.chatMessages = document.getElementById('chatMessages');
        this.loadingOverlay = document.getElementById('loadingOverlay');
        this.chatContainer = document.querySelector('.chat-container');
        this.welcomeGreeting = document.getElementById('welcomeGreeting');
        this.chatHistoryList = document.getElementById('chatHistoryList');
        
        // 初始化时检查是否需要居中
        this.checkAndSetCentered();
    }

    // 绑定事件监听器
    bindEvents() {
        // 新建对话
        if (this.newChatBtn) {
            this.newChatBtn.addEventListener('click', () => this.newChat());
        }
        
        // AI Ops按钮
        if (this.aiOpsSidebarBtn) {
            this.aiOpsSidebarBtn.addEventListener('click', () => this.triggerAIOps());
        }
        
        // 模式选择下拉菜单
        if (this.modeSelectorBtn) {
            this.modeSelectorBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleModeDropdown();
            });
        }
        
        // 下拉菜单项点击
        const dropdownItems = document.querySelectorAll('.dropdown-item');
        dropdownItems.forEach(item => {
            item.addEventListener('click', (e) => {
                const mode = item.getAttribute('data-mode');
                this.selectMode(mode);
                this.closeModeDropdown();
            });
        });
        
        // 点击外部关闭下拉菜单
        document.addEventListener('click', (e) => {
            if (!this.modeSelectorBtn.contains(e.target) && 
                !this.modeDropdown.contains(e.target)) {
                this.closeModeDropdown();
            }
        });
        
        // 发送消息
        if (this.sendButton) {
            this.sendButton.addEventListener('click', () => this.sendMessage());
        }
        
        if (this.messageInput) {
            this.messageInput.addEventListener('keypress', (e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    this.sendMessage();
                }
            });
        }
        
        // 工具按钮和菜单
        if (this.toolsBtn) {
            this.toolsBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleToolsMenu();
            });
        }
        
        // 工具菜单项点击事件
        if (this.uploadFileItem) {
            this.uploadFileItem.addEventListener('click', () => {
                if (this.fileInput) {
                    this.fileInput.click();
                }
                this.closeToolsMenu();
            });
        }
        
        // 点击外部关闭工具菜单
        document.addEventListener('click', (e) => {
            if (this.toolsBtn && this.toolsMenu && 
                !this.toolsBtn.contains(e.target) && 
                !this.toolsMenu.contains(e.target)) {
                this.closeToolsMenu();
            }
        });
        
        if (this.fileInput) {
            this.fileInput.addEventListener('change', (e) => this.handleFileSelect(e));
        }
    }

    // 切换工具菜单显示/隐藏
    toggleToolsMenu() {
        if (this.toolsMenu && this.toolsBtn) {
            const wrapper = this.toolsBtn.closest('.tools-btn-wrapper');
            if (wrapper) {
                wrapper.classList.toggle('active');
            }
        }
    }

    // 关闭工具菜单
    closeToolsMenu() {
        if (this.toolsMenu && this.toolsBtn) {
            const wrapper = this.toolsBtn.closest('.tools-btn-wrapper');
            if (wrapper) {
                wrapper.classList.remove('active');
            }
        }
    }

    // 新建对话
    newChat() {
        this.chatViewToken++;

        // 清空输入框
        if (this.messageInput) {
            this.messageInput.value = '';
        }
        
        // 清空当前对话历史
        this.currentChatHistory = [];
        
        // 重置标记
        this.isCurrentChatFromHistory = false;
        
        // 清空聊天记录
        if (this.chatMessages) {
            this.chatMessages.innerHTML = '';
        }
        
        // 生成新的会话ID
        this.sessionId = this.generateSessionId();
        
        // 新对话默认使用流式模式
        this.currentMode = 'stream';
        this.updateUI();
        
        // 重新设置居中样式（确保对话框居中显示）
        this.checkAndSetCentered();
        
        // 确保容器有过渡动画
        if (this.chatContainer) {
            this.chatContainer.style.transition = 'all 0.5s ease';
        }
        
        // 更新服务端历史对话列表
        this.renderChatHistory();
        this.loadChatHistories();
    }

    // 加载历史对话列表
    async loadChatHistories() {
        try {
            const response = await fetch(`${this.apiBaseUrl}/chat/sessions`, {
                headers: { 'Accept': 'application/json' }
            });
            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const payload = await response.json();
            if (payload.code !== 200) {
                throw new Error(payload.message || '获取会话列表失败');
            }

            this.chatHistories = (payload.data || []).map(session => ({
                id: session.sessionId,
                title: session.title || '新对话',
                messagePairCount: session.messagePairCount || 0,
                createdAt: session.createTime,
                updatedAt: session.updateTime
            }));
            this.renderChatHistory();
        } catch (e) {
            console.error('加载历史对话失败:', e);
            this.showNotification('加载历史对话失败: ' + e.message, 'error');
        }
    }

    // 渲染历史对话列表
    renderChatHistory() {
        if (!this.chatHistoryList) {
            return;
        }
        
        this.chatHistoryList.innerHTML = '';

        const serverHistories = [...this.chatHistories];
        const busySessionIds = new Set([
            ...this.sessionStreams.keys(),
            ...this.activeChatTasks.keys()
        ]);
        const generatingHistories = [];
        busySessionIds.forEach(sessionId => {
            const task = this.getBusyTask(sessionId) || {};
            const existingHistory = serverHistories.find(history => history.id === sessionId);
            if (existingHistory) {
                generatingHistories.push({
                    ...existingHistory,
                    updatedAt: task.startedAt || existingHistory.updatedAt
                });
            } else {
                generatingHistories.push({
                    id: sessionId,
                    title: task.question || '新对话',
                    messagePairCount: 0,
                    createdAt: task.startedAt || Date.now(),
                    updatedAt: task.startedAt || Date.now()
                });
            }
        });

        // 生成中的会话始终置顶，避免新发起的问题先落在列表底部。
        generatingHistories.sort((left, right) =>
            Number(right.updatedAt || right.createdAt || 0) -
            Number(left.updatedAt || left.createdAt || 0)
        );
        const histories = serverHistories.filter(history => !busySessionIds.has(history.id));
        histories.unshift(...generatingHistories);

        if (histories.length === 0) {
            return;
        }
        
        histories.forEach(history => {
            const busy = this.isSessionBusy(history.id);
            const historyItem = document.createElement('div');
            historyItem.className = `history-item${busy ? ' is-generating' : ''}`;
            historyItem.dataset.historyId = history.id;

            const historyContent = document.createElement('div');
            historyContent.className = 'history-item-content';
            const historyTitle = document.createElement('span');
            historyTitle.className = 'history-item-title';
            historyTitle.textContent = history.title || '新对话';
            historyContent.appendChild(historyTitle);
            historyItem.appendChild(historyContent);

            if (busy) {
                const spinner = document.createElement('span');
                const spinnerDurationMs = 1200;
                spinner.className = 'history-item-spinner';
                const startedAt = Number(history.updatedAt || history.createdAt) || Date.now();
                const elapsed = Math.max(0, Date.now() - startedAt);
                spinner.style.setProperty('--history-spinner-duration', `${spinnerDurationMs}ms`);
                // 补偿动画相位，列表对账重绘后圈圈不会从起点重新跳转。
                spinner.style.setProperty('--history-spinner-delay', `-${elapsed % spinnerDurationMs}ms`);
                spinner.title = '正在生成回复';
                spinner.setAttribute('role', 'status');
                spinner.setAttribute('aria-label', '正在生成回复');
                historyItem.appendChild(spinner);
            } else {
                const deleteButton = document.createElement('button');
                deleteButton.className = 'history-item-delete';
                deleteButton.type = 'button';
                deleteButton.dataset.historyId = history.id;
                deleteButton.title = '删除';
                deleteButton.setAttribute('aria-label', '删除对话');
                deleteButton.innerHTML = '<i data-lucide="trash-2" aria-hidden="true"></i>';
                deleteButton.addEventListener('click', event => {
                    event.stopPropagation();
                    this.deleteChatHistory(history.id);
                });
                historyItem.appendChild(deleteButton);
            }

            // 点击历史项加载对话
            historyItem.addEventListener('click', (e) => {
                if (!e.target.closest('.history-item-delete')) {
                    this.loadChatHistory(history.id);
                }
            });

            this.chatHistoryList.appendChild(historyItem);
        });

        this.renderLucideIcons();
    }
    
    // 加载服务端历史对话
    async loadChatHistory(historyId) {
        const viewToken = ++this.chatViewToken;

        try {
            const response = await fetch(
                `${this.apiBaseUrl}/chat/sessions/${encodeURIComponent(historyId)}/messages`,
                { headers: { 'Accept': 'application/json' } }
            );
            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const payload = await response.json();
            if (payload.code !== 200) {
                throw new Error(payload.message || '获取会话消息失败');
            }

            if (viewToken !== this.chatViewToken) {
                return;
            }

            const messages = (payload.data || []).map((message, index) => ({
                type: message.role,
                content: message.content,
                sequence: Number.isFinite(Number(message.sequence))
                    ? Number(message.sequence)
                    : index,
                timestamp: message.createdAt || null
            }));

            this.sessionId = historyId;
            this.currentChatHistory = messages;
            this.isCurrentChatFromHistory = true;
            this.updateUI();

            if (this.chatMessages) {
                this.chatMessages.innerHTML = '';
                messages.forEach(message => {
                    this.addMessage(
                        message.type,
                        message.content,
                        false,
                        false,
                        message.sequence,
                        true,
                        message.timestamp
                    );
                });
            }

            this.checkAndSetCentered();
            this.renderChatHistory();
            this.renderBusyPlaceholder(historyId);
        } catch (e) {
            console.error('加载历史对话失败:', e);
            this.showNotification('加载历史对话失败: ' + e.message, 'error');
        }
    }
    
    // 删除历史对话
    async deleteChatHistory(historyId) {
        if (this.isSessionBusy(historyId)) {
            this.showNotification('该对话正在生成回复，请完成后再删除', 'warning');
            return;
        }

        const history = this.chatHistories.find(h => h.id === historyId);
        if (!history) {
            return;
        }

        const confirmed = await this.showConfirm({
            title: '删除对话',
            message: `确定删除“${history.title}”吗？删除后无法恢复。`,
            confirmText: '删除',
            cancelText: '取消',
            destructive: true
        });

        if (!confirmed) {
            return;
        }

        try {
            const response = await fetch(
                `${this.apiBaseUrl}/chat/sessions/${encodeURIComponent(historyId)}`,
                { method: 'DELETE', headers: { 'Accept': 'application/json' } }
            );
            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const payload = await response.json();
            if (payload.code !== 200) {
                throw new Error(payload.message || '删除会话失败');
            }

            this.chatHistories = this.chatHistories.filter(h => h.id !== historyId);
            this.renderChatHistory();
        } catch (e) {
            console.error('删除历史对话失败:', e);
            this.showNotification('删除历史对话失败: ' + e.message, 'error');
            return;
        }
        
        // 如果删除的是当前对话，清空当前对话
        if (this.sessionId === historyId) {
            this.currentChatHistory = [];
            if (this.chatMessages) {
                this.chatMessages.innerHTML = '';
            }
            this.sessionId = this.generateSessionId();
            this.checkAndSetCentered();
        }
    }

    showConfirm({ title, message, confirmText = '确定', cancelText = '取消', destructive = false }) {
        return new Promise(resolve => {
            const overlay = document.createElement('div');
            overlay.className = 'confirm-dialog-overlay';

            const dialog = document.createElement('div');
            dialog.className = 'confirm-dialog';
            dialog.setAttribute('role', 'dialog');
            dialog.setAttribute('aria-modal', 'true');
            dialog.setAttribute('aria-labelledby', 'confirmDialogTitle');
            dialog.setAttribute('aria-describedby', 'confirmDialogMessage');

            const titleElement = document.createElement('h3');
            titleElement.id = 'confirmDialogTitle';
            titleElement.className = 'confirm-dialog-title';
            titleElement.textContent = this.stripEmoji(title);

            const messageElement = document.createElement('p');
            messageElement.id = 'confirmDialogMessage';
            messageElement.className = 'confirm-dialog-message';
            messageElement.textContent = this.stripEmoji(message);

            const actions = document.createElement('div');
            actions.className = 'confirm-dialog-actions';

            const cancelButton = document.createElement('button');
            cancelButton.type = 'button';
            cancelButton.className = 'confirm-dialog-cancel';
            cancelButton.textContent = this.stripEmoji(cancelText);

            const confirmButton = document.createElement('button');
            confirmButton.type = 'button';
            confirmButton.className = destructive ? 'confirm-dialog-confirm destructive' : 'confirm-dialog-confirm';
            confirmButton.textContent = this.stripEmoji(confirmText);

            actions.appendChild(cancelButton);
            actions.appendChild(confirmButton);
            dialog.appendChild(titleElement);
            dialog.appendChild(messageElement);
            dialog.appendChild(actions);
            overlay.appendChild(dialog);
            document.body.appendChild(overlay);

            let settled = false;
            const close = result => {
                if (settled) {
                    return;
                }
                settled = true;
                document.removeEventListener('keydown', handleKeydown);
                overlay.remove();
                resolve(result);
            };

            const handleKeydown = event => {
                if (event.key === 'Escape') {
                    close(false);
                }
            };

            cancelButton.addEventListener('click', () => close(false));
            confirmButton.addEventListener('click', () => close(true));
            overlay.addEventListener('click', event => {
                if (event.target === overlay) {
                    close(false);
                }
            });
            document.addEventListener('keydown', handleKeydown);
            cancelButton.focus();
        });
    }

    // 切换模式下拉菜单
    toggleModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) {
                wrapper.classList.toggle('active');
            }
        }
    }

    // 关闭模式下拉菜单
    closeModeDropdown() {
        if (this.modeSelectorBtn && this.modeDropdown) {
            const wrapper = this.modeSelectorBtn.closest('.mode-selector-wrapper');
            if (wrapper) {
                wrapper.classList.remove('active');
            }
        }
    }

    // 选择模式
    selectMode(mode) {
        this.currentMode = mode;
        this.updateUI();
        
        const modeNames = {
            'quick': '快速',
            'stream': '流式'
        };
        
        this.showNotification(`已切换到${modeNames[mode]}模式`, 'info');
    }

    // 更新UI
    updateUI() {
        // 更新模式选择器显示
        if (this.currentModeText) {
            const modeNames = {
                'quick': '快速',
                'stream': '流式'
            };
            this.currentModeText.textContent = modeNames[this.currentMode] || '流式';
        }
        
        // 更新下拉菜单选中状态
        const dropdownItems = document.querySelectorAll('.dropdown-item');
        dropdownItems.forEach(item => {
            const mode = item.getAttribute('data-mode');
            if (mode === this.currentMode) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });
        
        // 更新发送按钮状态
        if (this.sendButton) {
            this.sendButton.disabled = this.isStreaming;
        }
        
        // 更新输入框状态
        if (this.messageInput) {
            this.messageInput.disabled = this.isStreaming;
            this.messageInput.placeholder = '问问SoarerAlert值守助手';
        }
    }

    // 生成随机会话ID
    generateSessionId() {
        return 'session_' + Math.random().toString(36).substr(2, 9) + '_' + Date.now();
    }

    persistAssistantMessage(sessionId, content, sequence = null, timestamp = new Date().toISOString()) {
        if (!content) {
            return;
        }

        if (this.sessionId === sessionId) {
            this.currentChatHistory.push({
                type: 'assistant',
                content: content,
                sequence: Number.isFinite(Number(sequence))
                    ? Number(sequence)
                    : this.currentChatHistory.length,
                timestamp: timestamp
            });
        }

        this.loadChatHistories();
    }

    appendAssistantMessageToSession(sessionId, content) {
        const timestamp = new Date().toISOString();
        if (this.sessionId === sessionId) {
            const sequence = this.currentChatHistory.length;
            this.addMessage('assistant', content, false, false, sequence, true, timestamp);
            this.persistAssistantMessage(sessionId, content, sequence, timestamp);
            return;
        }
        this.persistAssistantMessage(sessionId, content, null, timestamp);
    }

    renderStreamingMessage(messageElement, content, sessionId) {
        if (this.sessionId !== sessionId) {
            return messageElement;
        }

        const localTask = this.sessionStreams.get(sessionId);
        if (localTask) {
            this.sessionStreams.set(sessionId, {
                ...localTask,
                partialAnswer: content
            });
        }

        let streamingElement = messageElement;
        if (!streamingElement || !streamingElement.isConnected) {
            streamingElement = this.renderBusyPlaceholder(sessionId);
            if (!streamingElement) {
                streamingElement = this.addMessage('assistant', '', true, false);
                streamingElement.dataset.sessionId = sessionId;
            }
        }

        if (!content) {
            return streamingElement;
        }

        const messageContent = streamingElement.querySelector('.message-content');
        if (messageContent) {
            streamingElement.className = 'message assistant streaming session-busy-placeholder';
            messageContent.innerHTML = this.renderStreamingMarkdown(content);
            this.highlightCodeBlocks(messageContent);
            this.scrollToBottom();
        }
        return streamingElement;
    }

    // 发送消息
    async sendMessage() {
        let message = '';
        if (this.messageInput) {
            message = this.messageInput.value.trim();
        }
        
        if (!message) {
            this.showNotification('请输入消息内容', 'warning');
            return;
        }

        if (this.isStreaming) {
            this.showNotification('请等待当前对话完成', 'warning');
            return;
        }

        const requestSessionId = this.sessionId;

        // 显示用户消息
        this.addMessage('user', message);
        
        // 清空输入框
        if (this.messageInput) {
            this.messageInput.value = '';
        }

        // 每个会话独立记录生成状态，允许其它会话继续操作。
        this.setSessionStreaming(requestSessionId, true, {
            question: message,
            regenerate: false
        });
        this.updateUI();

        try {
            if (this.currentMode === 'quick') {
                await this.sendQuickMessage(message, requestSessionId);
            } else if (this.currentMode === 'stream') {
                await this.sendStreamMessage(message, requestSessionId);
            }
        } catch (error) {
            console.error('发送消息失败:', error);
            this.appendAssistantMessageToSession(
                requestSessionId,
                '抱歉，发送消息时出现错误：' + error.message
            );
        } finally {
            this.setSessionStreaming(requestSessionId, false);
            this.updateUI();
        }
    }

    // 发送快速消息（普通对话）
    async sendQuickMessage(message, requestSessionId, requestOptions = {}) {
        // 添加等待提示消息
        const loadingMessage = this.addLoadingMessage('', true);
        loadingMessage.classList.add('session-busy-placeholder');
        loadingMessage.dataset.sessionId = requestSessionId;
        
        try {
            const response = await fetch(`${this.apiBaseUrl}/chat`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(this.buildChatRequest(message, requestSessionId, requestOptions))
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            const data = await response.json();
            console.log('[sendQuickMessage] 响应数据:', JSON.stringify(data));
            
            // 移除等待提示消息
            this.removeMessageElement(loadingMessage);
            
            // 统一响应格式：检查 data.code 或 data.message 判断请求是否成功
            if (data.code === 200 || data.message === 'success') {
                // data.data 是 ChatResponse 对象
                const chatResponse = data.data;
                
                if (chatResponse && chatResponse.success) {
                    // 成功：添加实际响应消息（即使 answer 为空也显示）
                    const answer = chatResponse.answer || '（无回复内容）';
                    this.appendAssistantMessageToSession(requestSessionId, answer);
                } else if (chatResponse && chatResponse.errorCode === 'AI_QUOTA_EXHAUSTED') {
                    this.appendAssistantMessageToSession(requestSessionId, chatResponse.errorMessage);
                } else if (chatResponse && chatResponse.errorMessage) {
                    // 业务错误
                    throw new Error(chatResponse.errorMessage);
                } else {
                    // 兜底：尝试显示任何可用内容
                    const fallbackAnswer = chatResponse?.answer || chatResponse?.errorMessage || '服务返回了空内容';
                    this.appendAssistantMessageToSession(requestSessionId, fallbackAnswer);
                }
            } else {
                // HTTP 成功但业务失败
                throw new Error(data.message || '请求失败');
            }
        } catch (error) {
            // 出错时也要移除等待提示消息
            this.removeMessageElement(loadingMessage);
            throw error;
        }
    }

    // 发送流式消息
    async sendStreamMessage(message, requestSessionId, requestOptions = {}) {
        const loadingMessage = this.addLoadingMessage('', true);
        loadingMessage.classList.add('session-busy-placeholder');
        loadingMessage.dataset.sessionId = requestSessionId;
        let assistantMessageElement = null;
        let fullResponse = '';
        this.updateSessionStream(requestSessionId, {
            partialAnswer: '',
            regenerate: Boolean(requestOptions.regenerate)
        });

        try {
            const response = await fetch(`${this.apiBaseUrl}/chat_stream`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(this.buildChatRequest(message, requestSessionId, requestOptions))
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            // 响应头已返回，切换为流式内容占位
            this.removeMessageElement(loadingMessage);
            assistantMessageElement = this.renderStreamingMessage(
                null,
                '',
                requestSessionId
            );

            // 处理流式响应
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = '';

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    
                    if (done) {
                        // 流结束，使用统一的处理方法
                        this.handleStreamComplete(assistantMessageElement, fullResponse, requestSessionId);
                        break;
                    }

                    // 解码数据并添加到缓冲区
                    buffer += decoder.decode(value, { stream: true });
                    
                    // 按行分割处理
                    const lines = buffer.split('\n');
                    // 保留最后一行（可能不完整）
                    buffer = lines.pop() || '';
                    
                    for (const line of lines) {
                        if (line.trim() === '') continue;
                        
                        console.log('[SSE调试] 收到行:', line);
                        
                        // 解析SSE格式
                        if (line.startsWith('id:')) {
                            console.log('[SSE调试] 解析到ID');
                            continue;
                        } else if (line.startsWith('event:')) {
                            // 兼容 "event:message" 和 "event: message" 两种格式
                            currentEvent = line.substring(6).trim();
                            console.log('[SSE调试] 解析到事件类型:', currentEvent);
                            // 注意：后端统一使用 "message" 事件名，真正的类型在 data 的 JSON 中
                            continue;
                        } else if (line.startsWith('data:')) {
                            // 兼容 "data:xxx" 和 "data: xxx" 两种格式
                            const rawData = line.substring(5).trim();
                            console.log('[SSE调试] 解析到数据, currentEvent:', currentEvent, ', rawData:', rawData);
                            
                            // 兼容旧格式 [DONE] 标记
                            if (rawData === '[DONE]') {
                                // 流结束标记，将内容转换为Markdown渲染
                                this.handleStreamComplete(assistantMessageElement, fullResponse, requestSessionId);
                                return;
                            }
                            
                            // 处理 SSE 数据
                            try {
                                // 尝试解析为 SseMessage 格式的 JSON
                                const sseMessage = JSON.parse(rawData);
                                console.log('[SSE调试] 解析JSON成功:', sseMessage);
                                
                                if (sseMessage && typeof sseMessage.type === 'string') {
                                    if (sseMessage.type === 'content') {
                                        const content = sseMessage.data || '';
                                        fullResponse += content;
                                        console.log('[SSE调试] 添加内容:', content);
                                        
                                        // 实时渲染 Markdown
                                        assistantMessageElement = this.renderStreamingMessage(
                                            assistantMessageElement,
                                            fullResponse,
                                            requestSessionId
                                        );
                                    } else if (sseMessage.type === 'done') {
                                        console.log('[SSE调试] 收到done标记，流结束');
                                        this.handleStreamComplete(assistantMessageElement, fullResponse, requestSessionId);
                                        return;
                                    } else if (sseMessage.type === 'error') {
                                        console.error('[SSE调试] 收到错误:', sseMessage.data);
                                        const errorText = sseMessage.code === 'AI_QUOTA_EXHAUSTED'
                                            ? (sseMessage.data || '')
                                            : '错误: ' + (sseMessage.data || '未知错误');
                                        this.handleStreamComplete(
                                            assistantMessageElement,
                                            errorText,
                                            requestSessionId
                                        );
                                        return;
                                    }
                                } else {
                                    // 不是标准 SseMessage 格式，尝试兼容处理
                                    console.log('[SSE调试] 非标准格式，尝试兼容处理');
                                    fullResponse += rawData;
                                    assistantMessageElement = this.renderStreamingMessage(
                                        assistantMessageElement,
                                        fullResponse,
                                        requestSessionId
                                    );
                                }
                            } catch (e) {
                                // JSON 解析失败，尝试兼容旧格式
                                console.log('[SSE调试] JSON解析失败，使用兼容模式:', e.message);
                                if (rawData === '') {
                                    fullResponse += '\n';
                                } else {
                                    fullResponse += rawData;
                                }
                                
                                assistantMessageElement = this.renderStreamingMessage(
                                    assistantMessageElement,
                                    fullResponse,
                                    requestSessionId
                                );
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            this.removeMessageElement(loadingMessage);
            throw error;
        }
    }

    // 添加消息到聊天界面
    addMessage(
        type,
        content,
        isStreaming = false,
        saveToHistory = true,
        sequence = null,
        showAssistantActions = true,
        timestamp = null
    ) {
        // 检查是否是第一条消息，如果是则移除居中样式
        const isFirstMessage = this.chatMessages && this.chatMessages.querySelectorAll('.message').length === 0;
        const messageSequence = Number.isFinite(Number(sequence))
            ? Number(sequence)
            : this.currentChatHistory.length;
        
        // 保存消息到当前对话历史（如果不是流式消息且需要保存）
        const messageTimestamp = timestamp || new Date().toISOString();

        if (!isStreaming && saveToHistory && content) {
            this.currentChatHistory.push({
                type: type,
                content: content,
                sequence: messageSequence,
                timestamp: messageTimestamp
            });
        }
        
        const messageDiv = document.createElement('div');
        messageDiv.className = `message ${type}${isStreaming ? ' streaming' : ''}`;
        messageDiv.dataset.sequence = String(messageSequence);
        messageDiv.__messageContent = content;
        messageDiv.__messageTimestamp = messageTimestamp;

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';
        
        // 如果是assistant消息且不是流式消息，使用Markdown渲染
        if (type === 'assistant' && !isStreaming) {
            messageContent.innerHTML = this.renderMarkdown(content);
            // 高亮代码块
            this.highlightCodeBlocks(messageContent);
        } else if (type === 'assistant') {
            messageContent.textContent = content;
        } else {
            // 用户消息或流式消息使用纯文本
            messageContent.textContent = this.stripEmoji(content);
        }

        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);
        if (type === 'assistant' && !isStreaming && showAssistantActions) {
            this.addAssistantActions(messageDiv, content);
        }
        if (type === 'user') {
            this.addUserActions(messageDiv, content);
        }

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            
            // 如果是第一条消息，移除居中样式并添加动画
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
                // 添加动画类
                this.chatContainer.style.transition = 'all 0.5s ease';
            }
            
            this.scrollToBottom();
        }

        this.renderLucideIcons();

        return messageDiv;
    }

    buildChatRequest(message, requestSessionId, requestOptions = {}) {
        const request = {
            Id: requestSessionId,
            Question: message
        };

        if (requestOptions.regenerate) {
            request.Regenerate = true;
            request.AssistantSequence = Number(requestOptions.assistantSequence);
        }

        return request;
    }

    addAssistantActions(messageElement, content) {
        if (!messageElement
            || !messageElement.classList.contains('assistant')
            || messageElement.classList.contains('aiops-message')
            || messageElement.classList.contains('streaming')) {
            return;
        }

        messageElement.__messageContent = content;

        const wrapper = messageElement.querySelector('.message-content-wrapper');
        if (!wrapper) {
            return;
        }

        const existingActions = wrapper.querySelector('.message-actions');
        if (existingActions) {
            existingActions.remove();
        }

        const actions = document.createElement('div');
        actions.className = 'message-actions';
        actions.innerHTML = `
            <button class="message-action-btn copy-message-btn" type="button"
                    title="复制" aria-label="复制">
                <i data-lucide="copy" aria-hidden="true"></i>
            </button>
            <button class="message-action-btn regenerate-message-btn" type="button"
                    title="重新生成" aria-label="重新生成">
                <i data-lucide="refresh-cw" aria-hidden="true"></i>
            </button>
            <span class="message-action-time"></span>
        `;

        actions.querySelector('.message-action-time')
            .textContent = this.formatMessageTime(messageElement.__messageTimestamp);
        actions.querySelector('.copy-message-btn')
            .addEventListener('click', () => this.copyMessage(messageElement));
        actions.querySelector('.regenerate-message-btn')
            .addEventListener('click', () => this.regenerateAssistantMessage(messageElement));

        wrapper.appendChild(actions);
        this.renderLucideIcons();
    }

    addUserActions(messageElement, content) {
        messageElement.__messageContent = content;

        const wrapper = messageElement.querySelector('.message-content-wrapper');
        if (!wrapper) {
            return;
        }

        const existingActions = wrapper.querySelector('.message-actions');
        if (existingActions) {
            existingActions.remove();
        }

        const actions = document.createElement('div');
        actions.className = 'message-actions user-message-actions';
        actions.innerHTML = `
            <span class="message-action-time"></span>
            <button class="message-action-btn copy-message-btn" type="button"
                    title="复制" aria-label="复制">
                <i data-lucide="copy" aria-hidden="true"></i>
            </button>
        `;

        actions.querySelector('.message-action-time')
            .textContent = this.formatMessageTime(messageElement.__messageTimestamp);
        actions.querySelector('.copy-message-btn')
            .addEventListener('click', () => this.copyMessage(messageElement));

        wrapper.appendChild(actions);
        this.renderLucideIcons();
    }

    showCopyCompleted(messageElement) {
        const copyButton = messageElement?.querySelector('.copy-message-btn');
        if (!copyButton) {
            return;
        }

        if (copyButton.__copyResetTimer) {
            window.clearTimeout(copyButton.__copyResetTimer);
        }

        copyButton.classList.add('is-copied');
        copyButton.title = '已复制';
        copyButton.setAttribute('aria-label', '已复制');
        copyButton.innerHTML = '<i data-lucide="check" aria-hidden="true"></i>';
        this.renderLucideIcons();

        copyButton.__copyResetTimer = window.setTimeout(() => {
            copyButton.classList.remove('is-copied');
            copyButton.title = '复制';
            copyButton.setAttribute('aria-label', '复制');
            copyButton.innerHTML = '<i data-lucide="copy" aria-hidden="true"></i>';
            this.renderLucideIcons();
            copyButton.__copyResetTimer = null;
        }, 1800);
    }

    async copyMessage(messageElement) {
        const content = messageElement && messageElement.__messageContent
            ? messageElement.__messageContent
            : '';
        if (!content) {
            return;
        }

        try {
            if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
                await navigator.clipboard.writeText(content);
            } else {
                this.copyTextWithFallback(content);
            }
            this.showCopyCompleted(messageElement);
            this.showNotification('已复制', 'success');
        } catch (error) {
            console.error('复制消息失败:', error);
            try {
                this.copyTextWithFallback(content);
                this.showCopyCompleted(messageElement);
                this.showNotification('已复制', 'success');
            } catch (fallbackError) {
                console.error('复制消息失败:', fallbackError);
                this.showNotification('复制失败，请手动选择文本', 'error');
            }
        }
    }

    parseMessageTimestamp(value) {
        if (!value) {
            return null;
        }
        if (value instanceof Date) {
            return Number.isNaN(value.getTime()) ? null : value;
        }

        const numericValue = Number(value);
        if (Number.isFinite(numericValue) && numericValue > 0) {
            return new Date(numericValue);
        }

        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? null : date;
    }

    formatMessageTime(value) {
        const messageDate = this.parseMessageTimestamp(value);
        if (!messageDate) {
            return '';
        }

        const now = new Date();
        const time = `${String(messageDate.getHours()).padStart(2, '0')}:${String(messageDate.getMinutes()).padStart(2, '0')}`;
        const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        const startOfYesterday = new Date(startOfToday);
        startOfYesterday.setDate(startOfYesterday.getDate() - 1);

        if (messageDate >= startOfToday) {
            return `今天 ${time}`;
        }
        if (messageDate >= startOfYesterday) {
            return `昨天 ${time}`;
        }

        const startOfWeek = new Date(startOfToday);
        startOfWeek.setDate(startOfWeek.getDate() - ((startOfWeek.getDay() + 6) % 7));
        if (messageDate >= startOfWeek) {
            const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
            return `${weekdays[messageDate.getDay()]} ${time}`;
        }

        if (messageDate.getFullYear() === now.getFullYear()) {
            return `${messageDate.getMonth() + 1}月${messageDate.getDate()}日 ${time}`;
        }
        return `${messageDate.getFullYear()}年${messageDate.getMonth() + 1}月${messageDate.getDate()}日 ${time}`;
    }

    copyTextWithFallback(content) {
        const textarea = document.createElement('textarea');
        textarea.value = content;
        textarea.setAttribute('readonly', '');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        const copied = document.execCommand('copy');
        textarea.remove();
        if (!copied) {
            throw new Error('浏览器拒绝复制');
        }
    }

    async regenerateAssistantMessage(messageElement) {
        if (this.isStreaming) {
            this.showNotification('请等待当前回复完成', 'warning');
            return;
        }

        const assistantSequence = Number(messageElement?.dataset?.sequence);
        if (!Number.isInteger(assistantSequence) || assistantSequence < 1) {
            this.showNotification('当前消息暂时无法重新生成', 'warning');
            return;
        }

        const userSequence = assistantSequence - 1;
        const userMessageElement = this.chatMessages
            ? Array.from(this.chatMessages.querySelectorAll('.message.user'))
                .find(element => Number(element.dataset.sequence) === userSequence)
            : null;
        const question = userMessageElement
            ? userMessageElement.querySelector('.message-content')?.textContent?.trim()
            : this.currentChatHistory[userSequence]?.content;

        if (!question) {
            this.showNotification('找不到对应的问题', 'warning');
            return;
        }

        const historySnapshot = this.currentChatHistory.map(message => ({ ...message }));
        const messagesSnapshot = this.chatMessages ? this.chatMessages.innerHTML : '';
        const requestSessionId = this.sessionId;

        // 重新生成从当前问题开始建立新的分支，保留问题本身和它之前的上下文。
        if (this.chatMessages) {
            Array.from(this.chatMessages.querySelectorAll('.message'))
                .filter(element => Number(element.dataset.sequence) >= assistantSequence)
                .forEach(element => element.remove());
        }
        this.currentChatHistory = this.currentChatHistory.slice(0, assistantSequence);

        this.setSessionStreaming(requestSessionId, true, {
            question,
            regenerate: true
        });
        this.updateUI();

        try {
            const requestOptions = {
                regenerate: true,
                assistantSequence
            };
            if (this.currentMode === 'stream') {
                await this.sendStreamMessage(question, requestSessionId, requestOptions);
            } else {
                await this.sendQuickMessage(question, requestSessionId, requestOptions);
            }
        } catch (error) {
            console.error('重新生成消息失败:', error);
            if (this.sessionId === requestSessionId) {
                this.restoreChatSnapshot(historySnapshot, messagesSnapshot);
            }
            this.showNotification('重新生成失败: ' + error.message, 'error');
        } finally {
            this.setSessionStreaming(requestSessionId, false);
            this.updateUI();
        }
    }

    restoreChatSnapshot(historySnapshot, messagesSnapshot) {
        this.currentChatHistory = historySnapshot;
        if (this.chatMessages) {
            this.chatMessages.innerHTML = messagesSnapshot;
            this.chatMessages.querySelectorAll('.message.user').forEach(message => {
                const sequence = Number(message.dataset.sequence);
                const historyMessage = this.currentChatHistory.find(item =>
                    item.type === 'user' && Number(item.sequence) === sequence
                );
                this.addUserActions(message, historyMessage ? historyMessage.content : '');
            });
            this.chatMessages.querySelectorAll('.message.assistant:not(.aiops-message)').forEach(message => {
                const sequence = Number(message.dataset.sequence);
                const historyMessage = this.currentChatHistory.find(item =>
                    item.type === 'assistant' && Number(item.sequence) === sequence
                );
                this.addAssistantActions(message, historyMessage ? historyMessage.content : '');
            });
            this.renderLucideIcons();
        }
        this.scrollToBottom();
    }

    removeMessageElement(element) {
        if (element && element.parentNode) {
            element.parentNode.removeChild(element);
        }
    }

    clearThinkingState(messageElement) {
        if (!messageElement) {
            return;
        }

        messageElement.classList.remove('thinking');
        const avatar = messageElement.querySelector('.message-avatar');
        if (avatar) {
            avatar.classList.remove('thinking-avatar');
        }
        const content = messageElement.querySelector('.message-content');
        if (content) {
            content.classList.remove('loading-message-content', 'thinking-message-content');
        }
    }

    // 添加带加载动画的消息
    addLoadingMessage(content, minimal = false) {
        const messageDiv = document.createElement('div');
        messageDiv.className = `message assistant thinking${minimal ? ' chat-thinking' : ''}`;

        if (!minimal) {
            messageDiv.classList.add('aiops-message');
        }

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        const messageContent = document.createElement('div');
        messageContent.className = 'message-content loading-message-content thinking-message-content';
        
        const loadingDots = document.createElement('span');
        loadingDots.className = 'thinking-dots';
        loadingDots.innerHTML = '<span></span><span></span><span></span>';
        
        if (!minimal) {
            const textSpan = document.createElement('span');
            textSpan.className = 'thinking-text';
            textSpan.textContent = this.stripEmoji(content);
            messageContent.appendChild(textSpan);
        }
        messageContent.appendChild(loadingDots);
        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);

        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            
            // 如果是第一条消息，移除居中样式
            const isFirstMessage = this.chatMessages.querySelectorAll('.message').length === 1;
            if (isFirstMessage && this.chatContainer) {
                this.chatContainer.classList.remove('centered');
                this.chatContainer.style.transition = 'all 0.5s ease';
            }
            
            this.scrollToBottom();
        }

        this.renderLucideIcons();

        return messageDiv;
    }
    
    // 检查并设置居中样式
    checkAndSetCentered() {
        if (this.chatMessages && this.chatContainer) {
            const hasMessages = this.chatMessages.querySelectorAll('.message').length > 0;
            if (!hasMessages) {
                this.chatContainer.classList.add('centered');
            } else {
                this.chatContainer.classList.remove('centered');
            }
        }
    }

    // 滚动到底部
    scrollToBottom() {
        if (this.chatMessages) {
            this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        }
    }

    // 处理流式传输完成
    handleStreamComplete(assistantMessageElement, fullResponse, requestSessionId) {
        let messageElement = assistantMessageElement;
        const isActiveSession = this.sessionId === requestSessionId;
        const completedAt = new Date().toISOString();

        if (isActiveSession) {
            if (!messageElement || !messageElement.isConnected) {
                messageElement = this.addMessage('assistant', '', true, false);
                messageElement.dataset.sessionId = requestSessionId;
            }
            messageElement.classList.remove('streaming', 'thinking', 'chat-thinking', 'session-busy-placeholder');
            messageElement.__messageTimestamp = completedAt;
            const messageContent = messageElement.querySelector('.message-content');
            if (messageContent) {
                messageContent.className = 'message-content';
                messageContent.innerHTML = this.renderMarkdown(fullResponse);
                // 高亮代码块
                this.highlightCodeBlocks(messageContent);
                this.addAssistantActions(messageElement, fullResponse);
                this.scrollToBottom();
            }
        } else if (messageElement && messageElement.isConnected) {
            messageElement.classList.remove('streaming');
        }

        // 保存流式消息到历史记录
        this.persistAssistantMessage(
            requestSessionId,
            fullResponse,
            messageElement ? Number(messageElement.dataset.sequence) : null,
            completedAt
        );
    }

    // 显示通知
    showNotification(message, type = 'info') {
        // 创建通知元素
        const notification = document.createElement('div');
        notification.className = `notification ${type}`;
        notification.textContent = this.stripEmoji(message);

        // 添加到页面
        document.body.appendChild(notification);

        // 3秒后自动移除
        setTimeout(() => {
            notification.style.animation = 'slideOut 0.3s ease';
            setTimeout(() => {
                if (notification.parentNode) {
                    notification.parentNode.removeChild(notification);
                }
            }, 300);
        }, 3000);
    }

    // 处理文件选择
    handleFileSelect(event) {
        const file = event.target.files[0];
        if (file) {
            // 验证文件格式
            if (!this.validateFileType(file)) {
                this.showNotification('只支持上传 TXT、Markdown、PDF、DOC 和 DOCX 格式的文件', 'error');
                this.fileInput.value = '';
                return;
            }
            this.uploadFile(file);
        }
    }

    // 验证文件类型
    validateFileType(file) {
        const fileName = file.name.toLowerCase();
        const allowedExtensions = ['.txt', '.md', '.markdown', '.pdf', '.doc', '.docx'];
        return allowedExtensions.some(ext => fileName.endsWith(ext));
    }

    // 上传文件到知识库
    async uploadFile(file) {
        // 再次验证文件类型（双重保险）
        if (!this.validateFileType(file)) {
            this.showNotification('只支持上传 TXT、Markdown、PDF、DOC 和 DOCX 格式的文件', 'error');
            return;
        }

        // 验证文件大小（限制为50MB）
        const maxSize = 50 * 1024 * 1024;
        if (file.size > maxSize) {
            this.showNotification('文件大小不能超过50MB', 'error');
            return;
        }

        const uploadSessionId = this.sessionId;

        // 锁定当前会话并显示上传遮罩层
        this.setSessionStreaming(uploadSessionId, true, {
            question: `上传文件 ${file.name}`
        });
        this.updateUI();
        this.showUploadOverlay(true, file.name);

        try {
            // 创建 FormData
            const formData = new FormData();
            formData.append('file', file);

            // 发送上传请求
            const response = await fetch(`${this.apiBaseUrl}/upload`, {
                method: 'POST',
                body: formData
            });

            const data = await response.json().catch(() => null);
            if (!response.ok) {
                throw new Error((data && data.message) || `HTTP错误: ${response.status}`);
            }

            if ((data.code === 200 || data.message === 'success') && data.data) {
                // 在聊天界面显示上传成功消息
                const uploadResult = data.data;
                const successMessage = uploadResult.duplicate
                    ? `${file.name} 已存在于知识库，系统跳过重复索引`
                    : `${file.name} 上传到知识库成功`;
                this.addMessage('assistant', successMessage, false, false, null, false);
            } else {
                throw new Error(data.message || '上传失败');
            }
        } catch (error) {
            console.error('文件上传失败:', error);
            this.showNotification(
                error.message === '额度已耗尽，请联系管理员重置~'
                    ? error.message
                    : '文件上传失败: ' + error.message,
                'error'
            );
        } finally {
            // 清空文件输入
            if (this.fileInput) {
                this.fileInput.value = '';
            }
            // 解锁当前会话
            this.setSessionStreaming(uploadSessionId, false);
            this.showUploadOverlay(false);
            this.updateUI();
        }
    }

    // 格式化文件大小
    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    }

    // 发送智能运维请求（SSE 流式模式）
    async sendAIOpsRequest(loadingMessageElement, requestSessionId) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/ai_ops`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ id: requestSessionId })
            });

            if (!response.ok) {
                throw new Error(`HTTP错误: ${response.status}`);
            }

            let fullResponse = '';

            // 处理 SSE 流式响应
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            let currentEvent = 'message'; // 默认事件类型为 message

            try {
                while (true) {
                    const { done, value } = await reader.read();
                    
                    if (done) {
                        // 流结束，更新最终内容
                        if (fullResponse) {
                            console.log('AI Ops 流结束，更新最终内容，长度:', fullResponse.length);
                            this.updateAIOpsMessage(
                                loadingMessageElement,
                                fullResponse,
                                [],
                                requestSessionId
                            );
                        }
                        break;
                    }

                    // 解码数据并添加到缓冲区
                    buffer += decoder.decode(value, { stream: true });
                    
                    // 按行分割处理
                    const lines = buffer.split('\n');
                    // 保留最后一行（可能不完整）
                    buffer = lines.pop() || '';
                    
                    for (const line of lines) {
                        if (line.trim() === '') continue;
                        
                        console.log('[AI Ops SSE] 收到行:', line);
                        
                        // 解析 SSE 格式
                        if (line.startsWith('id:')) {
                            continue;
                        } else if (line.startsWith('event:')) {
                            currentEvent = line.substring(6).trim();
                            console.log('[AI Ops SSE] 事件类型:', currentEvent);
                            continue;
                        } else if (line.startsWith('data:')) {
                            const rawData = line.substring(5).trim();
                            console.log('[AI Ops SSE] 数据:', rawData, ', currentEvent:', currentEvent);
                            
                            // 解析可能包含多个JSON对象的数据
                            const processJsonMessages = (data) => {
                                const jsonPattern = /\{"type"\s*:\s*"[^"]+"\s*,\s*"data"\s*:\s*(?:"[^"]*"|null)\}/g;
                                const matches = data.match(jsonPattern);
                                
                                if (matches && matches.length > 0) {
                                    console.log('[AI Ops SSE] 匹配到', matches.length, '个JSON对象');
                                    for (const jsonStr of matches) {
                                        try {
                                            const sseMessage = JSON.parse(jsonStr);
                                            if (sseMessage.type === 'content') {
                                                fullResponse += sseMessage.data || '';
                                            } else if (sseMessage.type === 'done') {
                                                console.log('AI Ops 流完成，最终内容长度:', fullResponse.length);
                                                this.updateAIOpsMessage(
                                                    loadingMessageElement,
                                                    fullResponse,
                                                    [],
                                                    requestSessionId
                                                );
                                                return true;
                                            } else if (sseMessage.type === 'error') {
                                                const error = new Error(sseMessage.data || '智能运维分析失败');
                                                error.code = sseMessage.code;
                                                throw error;
                                            }
                                        } catch (e) {
                                            if (e.code || e.message.includes('智能运维')) throw e;
                                            console.log('[AI Ops SSE] 单个JSON解析失败:', jsonStr);
                                        }
                                    }
                                    this.updateAIOpsStreamContent(
                                        loadingMessageElement,
                                        fullResponse,
                                        requestSessionId
                                    );
                                    return false;
                                }
                                return null;
                            };
                            
                            const result = processJsonMessages(rawData);
                            if (result === true) {
                                return; // 流结束
                            } else if (result === null) {
                                // 没有匹配到多个JSON，尝试单个JSON解析
                                try {
                                    const sseMessage = JSON.parse(rawData);
                                    if (sseMessage && sseMessage.type) {
                                        if (sseMessage.type === 'content') {
                                            fullResponse += sseMessage.data || '';
                                            this.updateAIOpsStreamContent(
                                                loadingMessageElement,
                                                fullResponse,
                                                requestSessionId
                                            );
                                        } else if (sseMessage.type === 'done') {
                                            console.log('AI Ops 流完成，最终内容长度:', fullResponse.length);
                                            this.updateAIOpsMessage(
                                                loadingMessageElement,
                                                fullResponse,
                                                [],
                                                requestSessionId
                                            );
                                            return;
                                        } else if (sseMessage.type === 'error') {
                                            const error = new Error(sseMessage.data || '智能运维分析失败');
                                            error.code = sseMessage.code;
                                            throw error;
                                        }
                                    } else {
                                        fullResponse += rawData;
                                        this.updateAIOpsStreamContent(
                                            loadingMessageElement,
                                            fullResponse,
                                            requestSessionId
                                        );
                                    }
                                } catch (e) {
                                    if (e.code || e.message.includes('智能运维')) throw e;
                                    // 非 JSON 格式，直接追加原始数据
                                    fullResponse += rawData;
                                    this.updateAIOpsStreamContent(
                                        loadingMessageElement,
                                        fullResponse,
                                        requestSessionId
                                    );
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.releaseLock();
            }
        } catch (error) {
            throw error;
        }
    }

    // 更新智能运维流式内容（实时显示）
    updateAIOpsStreamContent(messageElement, content, sessionId) {
        if (!sessionId) {
            if (!messageElement) return;
            messageElement.classList.add('aiops-message');
            this.clearThinkingState(messageElement);
            const messageContent = messageElement.querySelector('.message-content');
            if (messageContent) {
                messageContent.innerHTML = this.renderStreamingMarkdown(content);
                this.highlightCodeBlocks(messageContent);
                this.scrollToBottom();
            }
            return;
        }

        this.renderAIOpsStreamingMessage(messageElement, content, sessionId);
    }

    renderAIOpsStreamingMessage(messageElement, content, sessionId) {
        if (this.sessionId !== sessionId) {
            return messageElement;
        }

        const localTask = this.sessionStreams.get(sessionId);
        if (localTask) {
            this.sessionStreams.set(sessionId, {
                ...localTask,
                partialAnswer: content
            });
        }

        let streamingElement = messageElement;
        if (!streamingElement || !streamingElement.isConnected) {
            streamingElement = this.renderBusyPlaceholder(sessionId);
            if (!streamingElement) {
                streamingElement = this.addLoadingMessage('', true);
                streamingElement.dataset.sessionId = sessionId;
            }
        }

        streamingElement.dataset.sessionId = sessionId;
        streamingElement.className = 'message assistant aiops-message streaming session-busy-placeholder';
        this.clearThinkingState(streamingElement);

        const messageContentWrapper = streamingElement.querySelector('.message-content-wrapper');
        let messageContent = messageContentWrapper?.querySelector('.message-content');
        if (!messageContent) {
            messageContent = document.createElement('div');
            messageContent.className = 'message-content';
            messageContentWrapper?.appendChild(messageContent);
        }
        messageContent.innerHTML = this.renderStreamingMarkdown(content);
        this.highlightCodeBlocks(messageContent);
        this.scrollToBottom();
        return streamingElement;
    }

    // 更新智能运维消息（带折叠详情）
    updateAIOpsMessage(messageElement, response, details, sessionId) {
        console.log('updateAIOpsMessage 被调用');
        console.log('messageElement:', messageElement);
        console.log('response:', response);
        console.log('response length:', response ? response.length : 0);
        console.log('details:', details);
        
        if (sessionId && this.sessionId !== sessionId) {
            return null;
        }

        if (sessionId && (!messageElement || !messageElement.isConnected)) {
            messageElement = this.findBusyPlaceholder(sessionId);
            if (!messageElement) {
                messageElement = this.addLoadingMessage('', true);
                messageElement.dataset.sessionId = sessionId;
            }
        }

        if (!messageElement) {
            // 如果没有传入消息元素，则创建新消息
            console.log('messageElement 为空，创建新消息');
            return this.addAIOpsMessage(response, details);
        }

        // 添加aiops-message类
        messageElement.classList.add('aiops-message');
        this.clearThinkingState(messageElement);

        // 获取消息内容包装器
        const messageContentWrapper = messageElement.querySelector('.message-content-wrapper');
        if (!messageContentWrapper) {
            console.error('未找到 message-content-wrapper');
            return;
        }

        // 清空现有内容（保留消息内容容器）
        const messageContent = messageContentWrapper.querySelector('.message-content');
        if (!messageContent) {
            console.error('未找到 message-content');
            return;
        }

        messageContent.textContent = '';

        // 详情部分（可折叠）- 先显示
        if (details && details.length > 0) {
            // 检查是否已存在详情容器
            let detailsContainer = messageElement.querySelector('.aiops-details');
            if (!detailsContainer) {
                detailsContainer = document.createElement('div');
                detailsContainer.className = 'aiops-details';
                messageContentWrapper.insertBefore(detailsContainer, messageContent);
            } else {
                // 清空现有详情
                detailsContainer.innerHTML = '';
            }

            const detailsToggle = document.createElement('div');
            detailsToggle.className = 'details-toggle';
            detailsToggle.innerHTML = `
                <i class="toggle-icon" data-lucide="chevron-right" aria-hidden="true"></i>
                <span>查看详细步骤 (${details.length}条)</span>
            `;

            const detailsContent = document.createElement('div');
            detailsContent.className = 'details-content';
            
            details.forEach((detail, index) => {
                const detailItem = document.createElement('div');
                detailItem.className = 'detail-item';
                detailItem.innerHTML = `<strong>步骤 ${index + 1}:</strong> ${this.escapeHtml(detail)}`;
                detailsContent.appendChild(detailItem);
            });

            // 点击切换折叠状态
            detailsToggle.addEventListener('click', () => {
                detailsContent.classList.toggle('expanded');
                detailsToggle.classList.toggle('expanded');
            });

            detailsContainer.appendChild(detailsToggle);
            detailsContainer.appendChild(detailsContent);
        }

        // 更新主要响应内容（使用Markdown渲染）
        console.log('开始渲染 Markdown');
        const renderedHtml = this.renderMarkdown(response);
        console.log('Markdown 渲染完成，HTML 长度:', renderedHtml ? renderedHtml.length : 0);
        messageContent.innerHTML = renderedHtml;
        console.log('innerHTML 已设置');
        // 高亮代码块
        this.highlightCodeBlocks(messageContent);
        console.log('代码块高亮完成');
        
        this.renderLucideIcons();
        this.scrollToBottom();
        return messageElement;
    }

    // 添加智能运维消息（带折叠详情）- 保留用于兼容性
    addAIOpsMessage(response, details) {
        const messageDiv = document.createElement('div');
        messageDiv.className = 'message assistant aiops-message';

        // 创建消息内容包装器
        const messageContentWrapper = document.createElement('div');
        messageContentWrapper.className = 'message-content-wrapper';

        // 详情部分（可折叠）- 先显示
        if (details && details.length > 0) {
            const detailsContainer = document.createElement('div');
            detailsContainer.className = 'aiops-details';

            const detailsToggle = document.createElement('div');
            detailsToggle.className = 'details-toggle';
            detailsToggle.innerHTML = `
                <i class="toggle-icon" data-lucide="chevron-right" aria-hidden="true"></i>
                <span>查看详细步骤 (${details.length}条)</span>
            `;

            const detailsContent = document.createElement('div');
            detailsContent.className = 'details-content';
            
            details.forEach((detail, index) => {
                const detailItem = document.createElement('div');
                detailItem.className = 'detail-item';
                detailItem.innerHTML = `<strong>步骤 ${index + 1}:</strong> ${this.escapeHtml(detail)}`;
                detailsContent.appendChild(detailItem);
            });

            // 点击切换折叠状态
            detailsToggle.addEventListener('click', () => {
                detailsContent.classList.toggle('expanded');
                detailsToggle.classList.toggle('expanded');
            });

            detailsContainer.appendChild(detailsToggle);
            detailsContainer.appendChild(detailsContent);
            messageContentWrapper.appendChild(detailsContainer);
        }

        // 主要响应内容 - 后显示（使用Markdown渲染）
        const messageContent = document.createElement('div');
        messageContent.className = 'message-content';
        messageContent.innerHTML = this.renderMarkdown(response);
        // 高亮代码块
        this.highlightCodeBlocks(messageContent);
        messageContentWrapper.appendChild(messageContent);
        messageDiv.appendChild(messageContentWrapper);
        
        if (this.chatMessages) {
            this.chatMessages.appendChild(messageDiv);
            this.renderLucideIcons();
            this.scrollToBottom();
        }

        return messageDiv;
    }

    // HTML转义
    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // 触发智能运维（点击智能运维按钮时直接调用）
    async triggerAIOps() {
        if (this.isStreaming) {
            this.showNotification('请等待当前操作完成', 'warning');
            return;
        }

        // 新建对话
        this.newChat();
        
        // AI Ops 使用与普通对话一致的三点加载状态
        const aiOpsSessionId = this.sessionId;
        const loadingMessage = this.addLoadingMessage('', true);
        this.currentAIOpsMessage = loadingMessage; // 保存消息引用用于后续更新
        
        // 设置发送状态
        this.setSessionStreaming(aiOpsSessionId, true, {
            question: '智能运维诊断'
        });
        this.updateUI();

        try {
            await this.sendAIOpsRequest(loadingMessage, aiOpsSessionId);
        } catch (error) {
            console.error('智能运维分析失败:', error);
            this.handleAIOpsError(aiOpsSessionId, error, loadingMessage);
        } finally {
            // 先让服务端会话进入列表，再移除本地生成占位，避免任务结束后列表项消失。
            await this.loadChatHistories();
            this.setSessionStreaming(aiOpsSessionId, false);
            if (this.sessionId === aiOpsSessionId) {
                await this.loadChatHistory(aiOpsSessionId);
            }
            this.currentAIOpsMessage = null;
            this.updateUI();
        }
    }

    handleAIOpsError(sessionId, error, loadingMessage = null) {
        const errorMessage = error.code === 'AI_QUOTA_EXHAUSTED'
            ? error.message
            : this.stripEmoji('抱歉，智能运维分析时出现错误：' + error.message);

        if (this.sessionId !== sessionId) {
            return;
        }

        let messageElement = loadingMessage?.isConnected
            ? loadingMessage
            : this.findBusyPlaceholder(sessionId);
        if (!messageElement) {
            messageElement = this.addLoadingMessage('', true);
            messageElement.dataset.sessionId = sessionId;
        }

        this.updateAIOpsMessage(messageElement, errorMessage, [], sessionId);
        this.currentChatHistory.push({
            type: 'assistant',
            content: errorMessage,
            sequence: this.currentChatHistory.length,
            timestamp: new Date().toISOString()
        });
    }

    // 显示/隐藏加载遮罩层
    showLoadingOverlay(show) {
        if (this.loadingOverlay) {
            if (show) {
                this.loadingOverlay.style.display = 'flex';
                // 更新文字为智能运维
                const loadingText = this.loadingOverlay.querySelector('.loading-text');
                const loadingSubtext = this.loadingOverlay.querySelector('.loading-subtext');
                if (loadingText) loadingText.textContent = this.stripEmoji('智能运维分析中，请稍候');
                if (loadingSubtext) loadingSubtext.textContent = this.stripEmoji('后端正在处理，请耐心等待');
                // 防止页面滚动
                document.body.style.overflow = 'hidden';
            } else {
                this.loadingOverlay.style.display = 'none';
                // 恢复页面滚动
                document.body.style.overflow = '';
            }
        }
    }

    // 显示/隐藏上传遮罩层
    showUploadOverlay(show, fileName = '') {
        if (this.loadingOverlay) {
            if (show) {
                this.loadingOverlay.style.display = 'flex';
                // 更新文字为上传中
                const loadingText = this.loadingOverlay.querySelector('.loading-text');
                const loadingSubtext = this.loadingOverlay.querySelector('.loading-subtext');
                if (loadingText) loadingText.textContent = this.stripEmoji('正在上传文件');
                if (loadingSubtext) loadingSubtext.textContent = this.stripEmoji(fileName ? `上传: ${fileName}` : '请稍候');
                // 防止页面滚动
                document.body.style.overflow = 'hidden';
            } else {
                this.loadingOverlay.style.display = 'none';
                // 恢复页面滚动
                document.body.style.overflow = '';
            }
        }
    }
}

// 添加CSS动画
const style = document.createElement('style');
style.textContent = `
    @keyframes slideIn {
        from {
            transform: translateX(100%);
            opacity: 0;
        }
        to {
            transform: translateX(0);
            opacity: 1;
        }
    }
    
    @keyframes slideOut {
        from {
            transform: translateX(0);
            opacity: 1;
        }
        to {
            transform: translateX(100%);
            opacity: 0;
        }
    }
`;
document.head.appendChild(style);

// 初始化应用
document.addEventListener('DOMContentLoaded', () => {
    new SoarerAlertAgentApp();
});
