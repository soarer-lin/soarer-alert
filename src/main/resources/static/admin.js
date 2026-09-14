(function () {
    'use strict';

    const userState = {page: 0, totalPages: 0};
    const auditState = {page: 0, totalPages: 0};
    let resetUserId = null;
    let quotaUserId = null;

    const userRows = document.getElementById('userRows');
    const auditRows = document.getElementById('auditRows');
    const userError = document.getElementById('userError');
    const resetModal = document.getElementById('resetModal');
    const resetError = document.getElementById('resetError');
    const resetTarget = document.getElementById('resetTarget');
    const resetPasswordInput = document.getElementById('resetPassword');
    const quotaModal = document.getElementById('quotaModal');
    const quotaError = document.getElementById('quotaError');
    const quotaTarget = document.getElementById('quotaTarget');
    const quotaPreset = document.getElementById('quotaPreset');
    const quotaCustomField = document.getElementById('quotaCustomField');
    const quotaCustom = document.getElementById('quotaCustom');

    function escapeHtml(value) {
        return String(value === null || value === undefined ? '' : value)
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#39;');
    }

    async function request(path, options) {
        const response = await fetch(path, {...options, credentials: 'same-origin'});
        const payload = await response.json().catch(() => null);
        if (!response.ok || !payload || payload.code !== 200) {
            throw new Error((payload && payload.message) || '请求失败');
        }
        return payload.data;
    }

    function showUserError(message) {
        userError.textContent = message;
        userError.classList.remove('hidden');
    }

    function hideUserError() {
        userError.classList.add('hidden');
    }

    function formatTime(value) {
        return value ? String(value).replace('T', ' ').slice(0, 19) : '-';
    }

    function formatQuota(user) {
        if (user.aiQuotaLimit === null || user.aiQuotaLimit === undefined) {
            return '无限制';
        }
        return `${user.aiQuotaRemaining ?? 0} / ${user.aiQuotaLimit}`;
    }

    function refreshIcons() {
        if (window.lucide) {
            window.lucide.createIcons();
        }
    }

    async function loadUsers() {
        try {
            const data = await request(`/api/admin/users?page=${userState.page}&size=20`);
            userState.totalPages = data.totalPages;
            userState.page = data.page;
            document.getElementById('userPageInfo').textContent = `${data.page + 1} / ${Math.max(data.totalPages, 1)}`;
            document.getElementById('prevUsers').disabled = data.page <= 0;
            document.getElementById('nextUsers').disabled = data.page + 1 >= data.totalPages;
            userRows.innerHTML = data.content.map(user => `
                <tr>
                    <td>${escapeHtml(user.username)}</td>
                    <td>${escapeHtml(user.displayName || '-')}</td>
                    <td>${escapeHtml(user.role)}</td>
                    <td><span class="auth-status ${user.status === 'DISABLED' ? 'disabled' : ''}">${escapeHtml(user.status)}</span></td>
                    <td>${escapeHtml(formatTime(user.lastLoginAt))}</td>
                    <td>${escapeHtml(formatQuota(user))}</td>
                    <td>
                        <div class="auth-toolbar compact">
                            <button class="auth-button compact" type="button" data-edit="${user.id}" data-name="${escapeHtml(user.displayName || '')}" data-role="${user.role}">编辑</button>
                            <button class="auth-button compact" type="button" data-reset="${user.id}" data-username="${escapeHtml(user.username)}">重置密码</button>
                            <button class="auth-button compact" type="button" data-quota="${user.id}" data-username="${escapeHtml(user.username)}" data-quota-limit="${user.aiQuotaLimit === null || user.aiQuotaLimit === undefined ? '' : user.aiQuotaLimit}">设置次数</button>
                            ${user.status === 'DISABLED'
                                ? `<button class="auth-button compact" type="button" data-enable="${user.id}">启用</button>`
                                : `<button class="auth-button compact danger" type="button" data-disable="${user.id}">禁用</button>`}
                            <button class="auth-button compact" type="button"
                                    data-demo-login="${user.id}"
                                    data-enabled="${user.demoLoginEnabled ? 'true' : 'false'}">
                                <i data-lucide="flask-conical"></i>
                                <span>${user.demoLoginEnabled ? '取消测试账号' : '设为测试账号'}</span>
                            </button>
                            ${user.passwordChangeLocked
                                ? `<button class="auth-button compact" type="button" data-unlock-password="${user.id}">
                                    <i data-lucide="unlock"></i><span>允许改密</span>
                                </button>`
                                : `<button class="auth-button compact" type="button" data-lock-password="${user.id}">
                                    <i data-lucide="lock"></i><span>禁改密码</span>
                                </button>`}
                        </div>
                    </td>
                </tr>
            `).join('');
            refreshIcons();
        } catch (error) {
            showUserError(error.message);
        }
    }

    async function loadAudit() {
        try {
            const data = await request(`/api/admin/login-audit?page=${auditState.page}&size=20`);
            auditState.totalPages = data.totalPages;
            auditState.page = data.page;
            document.getElementById('auditPageInfo').textContent = `${data.page + 1} / ${Math.max(data.totalPages, 1)}`;
            document.getElementById('prevAudit').disabled = data.page <= 0;
            document.getElementById('nextAudit').disabled = data.page + 1 >= data.totalPages;
            auditRows.innerHTML = data.content.map(item => `
                <tr>
                    <td>${escapeHtml(formatTime(item.createdAt))}</td>
                    <td>${escapeHtml(item.username || '-')}</td>
                    <td><span class="auth-status ${item.success ? '' : 'disabled'}">${item.success ? 'SUCCESS' : 'FAILED'}</span></td>
                    <td>${escapeHtml(item.ipAddress || '-')}</td>
                    <td>${escapeHtml(item.failReason || '-')}</td>
                </tr>
            `).join('');
        } catch (error) {
            showUserError(error.message);
        }
    }

    document.getElementById('createForm').addEventListener('submit', async event => {
        event.preventDefault();
        hideUserError();
        const button = document.getElementById('createButton');
        button.disabled = true;
        try {
            await request('/api/admin/users', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    username: document.getElementById('createUsername').value.trim(),
                    displayName: document.getElementById('createDisplayName').value.trim(),
                    role: document.getElementById('createRole').value,
                    password: document.getElementById('createPassword').value
                })
            });
            event.target.reset();
            userState.page = 0;
            await loadUsers();
        } catch (error) {
            showUserError(error.message);
        } finally {
            button.disabled = false;
        }
    });

    userRows.addEventListener('click', async event => {
        const editButton = event.target.closest('[data-edit]');
        const resetButton = event.target.closest('[data-reset]');
        const disableButton = event.target.closest('[data-disable]');
        const enableButton = event.target.closest('[data-enable]');
        const lockPasswordButton = event.target.closest('[data-lock-password]');
        const unlockPasswordButton = event.target.closest('[data-unlock-password]');
        const quotaButton = event.target.closest('[data-quota]');
        const demoLoginButton = event.target.closest('[data-demo-login]');

        try {
            if (editButton) {
                const displayName = window.prompt('新的姓名', editButton.dataset.name || '');
                if (displayName === null) {
                    return;
                }
                const role = window.prompt('角色（ADMIN / OPS）', editButton.dataset.role || 'OPS');
                if (role === null || role.trim() === '') {
                    return;
                }
                await request(`/api/admin/users/${editButton.dataset.edit}`, {
                    method: 'PUT',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({displayName: displayName.trim(), role: role.trim().toUpperCase()})
                });
                await loadUsers();
            } else if (resetButton) {
                resetUserId = resetButton.dataset.reset;
                resetTarget.textContent = `账号：${resetButton.dataset.username}`;
                resetError.classList.add('hidden');
                resetPasswordInput.value = '';
                resetModal.classList.remove('hidden');
                resetPasswordInput.focus();
            } else if (quotaButton) {
                quotaUserId = quotaButton.dataset.quota;
                quotaTarget.textContent = `账号：${quotaButton.dataset.username}`;
                quotaError.classList.add('hidden');
                const currentLimit = quotaButton.dataset.quotaLimit;
                if (currentLimit === '') {
                    quotaPreset.value = 'unlimited';
                    quotaCustom.value = '';
                } else if (['10', '30', '50'].includes(currentLimit)) {
                    quotaPreset.value = currentLimit;
                    quotaCustom.value = currentLimit;
                } else {
                    quotaPreset.value = 'custom';
                    quotaCustom.value = currentLimit;
                }
                quotaCustomField.classList.toggle('hidden', quotaPreset.value !== 'custom');
                quotaModal.classList.remove('hidden');
                quotaPreset.focus();
            } else if (disableButton) {
                await request(`/api/admin/users/${disableButton.dataset.disable}/disable`, {method: 'POST'});
                await loadUsers();
            } else if (enableButton) {
                await request(`/api/admin/users/${enableButton.dataset.enable}/enable`, {method: 'POST'});
                await loadUsers();
            } else if (lockPasswordButton) {
                await request(`/api/admin/users/${lockPasswordButton.dataset.lockPassword}/lock-password`, {method: 'POST'});
                await loadUsers();
            } else if (unlockPasswordButton) {
                await request(`/api/admin/users/${unlockPasswordButton.dataset.unlockPassword}/unlock-password`, {method: 'POST'});
                await loadUsers();
            } else if (demoLoginButton) {
                const action = demoLoginButton.dataset.enabled === 'true' ? 'disable' : 'enable';
                await request(`/api/admin/users/${demoLoginButton.dataset.demoLogin}/${action}-demo-login`, {method: 'POST'});
                await loadUsers();
            }
        } catch (error) {
            showUserError(error.message);
        }
    });

    document.getElementById('resetForm').addEventListener('submit', async event => {
        event.preventDefault();
        resetError.classList.add('hidden');
        const button = document.getElementById('resetButton');
        button.disabled = true;
        try {
            await request(`/api/admin/users/${resetUserId}/reset-password`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({password: resetPasswordInput.value})
            });
            resetModal.classList.add('hidden');
            await loadUsers();
        } catch (error) {
            resetError.textContent = error.message;
            resetError.classList.remove('hidden');
        } finally {
            button.disabled = false;
        }
    });

    document.getElementById('cancelReset').addEventListener('click', () => {
        resetModal.classList.add('hidden');
    });

    quotaPreset.addEventListener('change', () => {
        quotaCustomField.classList.toggle('hidden', quotaPreset.value !== 'custom');
        if (quotaPreset.value === 'custom') {
            quotaCustom.focus();
        }
    });

    document.getElementById('cancelQuota').addEventListener('click', () => {
        quotaModal.classList.add('hidden');
    });

    document.getElementById('quotaForm').addEventListener('submit', async event => {
        event.preventDefault();
        quotaError.classList.add('hidden');
        const button = document.getElementById('quotaButton');
        button.disabled = true;
        try {
            let aiQuotaLimit = null;
            if (quotaPreset.value === 'custom') {
                const customValue = Number(quotaCustom.value);
                if (!Number.isInteger(customValue) || customValue < 1 || customValue > 999999) {
                    throw new Error('次数必须是 1 到 999999 之间的正整数');
                }
                aiQuotaLimit = customValue;
            } else if (quotaPreset.value !== 'unlimited') {
                aiQuotaLimit = Number(quotaPreset.value);
            }
            await request(`/api/admin/users/${quotaUserId}/ai-quota`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({aiQuotaLimit})
            });
            quotaModal.classList.add('hidden');
            await loadUsers();
        } catch (error) {
            quotaError.textContent = error.message;
            quotaError.classList.remove('hidden');
        } finally {
            button.disabled = false;
        }
    });

    document.getElementById('prevUsers').addEventListener('click', () => {
        if (userState.page > 0) {
            userState.page--;
            loadUsers();
        }
    });
    document.getElementById('nextUsers').addEventListener('click', () => {
        if (userState.page + 1 < userState.totalPages) {
            userState.page++;
            loadUsers();
        }
    });
    document.getElementById('prevAudit').addEventListener('click', () => {
        if (auditState.page > 0) {
            auditState.page--;
            loadAudit();
        }
    });
    document.getElementById('nextAudit').addEventListener('click', () => {
        if (auditState.page + 1 < auditState.totalPages) {
            auditState.page++;
            loadAudit();
        }
    });

    loadUsers();
    loadAudit();
})();
