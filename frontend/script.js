/**
 * AI工坊前端主脚本
 * 
 * <p>负责处理以下功能：</p>
 * <ul>
 *   <li>工具卡片交互</li>
 *   <li>任务提交弹窗</li>
 *   <li>文件上传处理</li>
 *   <li>用户名密码登录/注册</li>
 *   <li>任务历史管理</li>
 *   <li>与后端API交互</li>
 * </ul>
 */

/**
 * 工具配置映射表
 * 
 * <p>key: 工具标识</p>
 * <p>value: 工具配置对象，包含：</p>
 * <ul>
 *   <li>name: 工具名称</li>
 *   <li>needText: 是否需要文字输入</li>
 *   <li>needImage: 是否需要图片上传</li>
 *   <li>needVideo: 是否需要视频上传</li>
 *   <li>needDoc: 是否需要文档上传</li>
 *   <li>isAdvanced: 是否为高级工具</li>
 * </ul>
 */
const tools = {
    'text-to-image': { name: '文字生成图片', needText: true, needImage: false, needVideo: false },
    'text-to-video': { name: '文字生成视频', needText: true, needImage: false, needVideo: false },
    'image-to-video': { name: '图片生成视频', needText: true, needImage: true, needVideo: false },
    'text-to-video-audio': { name: '文字生成带音频视频', needText: true, needImage: false, needVideo: false },
    'image-to-video-audio': { name: '图片生成带音频视频', needText: true, needImage: true, needVideo: false },
    'video-remove-subtitle': { name: '视频去字幕', needText: false, needImage: false, needVideo: true },
    'face-consistency': { name: '人物一致性迁移', needText: true, needImage: true, needVideo: false },
    'text-to-speech': { name: '文字生成语音', needText: true, needImage: false, needVideo: false },
    'drama-generation': { name: '短剧生成', needText: false, needDoc: true, needImage: false, needVideo: false, isAdvanced: true }
};

/** 后端API基础地址 */
const API_BASE_URL = '/api';

/** 登录有效期（2小时），单位：毫秒 */
const LOGIN_EXPIRE_TIME = 2 * 60 * 60 * 1000;

/** 自动检查登录状态的间隔（1分钟），单位：毫秒 */
const AUTO_CHECK_INTERVAL = 60 * 1000;

/** 当前选中的工具ID */
let currentTool = null;

/** 已上传的文件列表 */
let uploadedFiles = [];

/** 用户登录状态 */
let isLoggedIn = false;

/** 当前用户ID */
let currentUserId = null;

/** 当前用户名 */
let currentUsername = null;

/** 登录时间戳（毫秒） */
let loginTime = null;

/** 自动登出检查定时器ID */
let autoLogoutTimer = null;

/**
 * 页面初始化
 * 
 * <p>页面加载完成后执行以下初始化：</p>
 * <ol>
 *   <li>工具卡片初始化</li>
 *   <li>弹窗初始化</li>
 *   <li>文件上传初始化</li>
 *   <li>登录模块初始化</li>
 *   <li>导航按钮初始化</li>
 *   <li>检查登录状态（包含登录过期检查）</li>
 *   <li>启动自动登出检查定时器</li>
 * </ol>
 */
document.addEventListener('DOMContentLoaded', () => {
    initToolCards();
    initModal();
    initUpload();
    initLogin();
    initNavigation();
    checkLoginStatus();
    startAutoLogoutChecker();
});

/**
 * 初始化工具卡片事件监听
 * 
 * <p>为每个工具卡片的"开始使用"按钮添加点击事件。</p>
 * <p>未登录用户点击时提示登录。</p>
 */
function initToolCards() {
    const toolCards = document.querySelectorAll('.tool-card');
    toolCards.forEach(card => {
        const toolBtn = card.querySelector('.tool-btn');
        toolBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            // 检查登录状态
            if (!isLoggedIn) {
                showNotification('提示', '请先登录后再使用', 'warning');
                document.getElementById('loginBtn').click();
                return;
            }
            const toolId = card.dataset.tool;
            openModal(toolId);
        });
    });
}

/**
 * 初始化任务提交弹窗
 * 
 * <p>为弹窗的关闭、取消、提交按钮添加事件监听。</p>
 */
function initModal() {
    const modalOverlay = document.getElementById('modalOverlay');
    const modalClose = document.getElementById('modalClose');
    const cancelBtn = document.getElementById('cancelBtn');
    const submitBtn = document.getElementById('submitBtn');

    modalClose.addEventListener('click', closeModal);
    cancelBtn.addEventListener('click', closeModal);

    // 点击弹窗外部关闭弹窗
    modalOverlay.addEventListener('click', (e) => {
        if (e.target === modalOverlay) {
            closeModal();
        }
    });

    submitBtn.addEventListener('click', submitTaskToBackend);
}

/**
 * 初始化文件上传功能
 * 
 * <p>为上传区域添加点击、拖拽、文件选择事件监听。</p>
 */
function initUpload() {
    const uploadArea = document.getElementById('uploadArea');
    const fileInput = document.getElementById('fileInput');

    // 点击上传区域触发文件选择
    uploadArea.addEventListener('click', () => {
        fileInput.click();
    });

    // 拖拽进入高亮效果
    uploadArea.addEventListener('dragover', (e) => {
        e.preventDefault();
        uploadArea.classList.add('dragover');
    });

    // 拖拽离开取消高亮
    uploadArea.addEventListener('dragleave', () => {
        uploadArea.classList.remove('dragover');
    });

    // 放置文件处理
    uploadArea.addEventListener('drop', (e) => {
        e.preventDefault();
        uploadArea.classList.remove('dragover');
        const files = Array.from(e.dataTransfer.files);
        handleFiles(files);
    });

    // 文件选择变化处理
    fileInput.addEventListener('change', (e) => {
        const files = Array.from(e.target.files);
        handleFiles(files);
    });
}

/**
 * 初始化登录模块
 * 
 * <p>为登录按钮、登录弹窗、关闭按钮添加事件监听。</p>
 * <p>添加登录/注册表单事件监听。</p>
 */
function initLogin() {
    const loginBtn = document.getElementById('loginBtn');
    const loginModalOverlay = document.getElementById('loginModalOverlay');
    const loginModalClose = document.querySelector('.login-modal-close');
    
    // 打开登录弹窗
    loginBtn.addEventListener('click', () => {
        if (!isLoggedIn) {
            loginModalOverlay.classList.add('active');
            showLoginForm();
        }
    });
    
    // 关闭登录弹窗
    loginModalClose.addEventListener('click', () => {
        loginModalOverlay.classList.remove('active');
    });
    
    loginModalOverlay.addEventListener('click', (e) => {
        if (e.target === loginModalOverlay) {
            loginModalOverlay.classList.remove('active');
        }
    });

    // 登录按钮点击事件
    document.getElementById('doLoginBtn').addEventListener('click', doLogin);
    
    // 注册按钮点击事件
    document.getElementById('doRegisterBtn').addEventListener('click', doRegister);
    
    // 切换到注册表单
    document.getElementById('switchToRegisterBtn').addEventListener('click', showRegisterForm);
    
    // 切换到登录表单
    document.getElementById('switchToLoginBtn').addEventListener('click', showLoginForm);
    
    // 实时检查用户名是否已存在
    document.getElementById('registerUsername').addEventListener('blur', checkUsernameExists);
}

/**
 * 显示登录表单
 */
function showLoginForm() {
    document.querySelector('.login-form').style.display = 'block';
    document.getElementById('registerForm').style.display = 'none';
    document.getElementById('loginModalTitle').textContent = '✨ 用户登录 ✨';
}

/**
 * 显示注册表单
 */
function showRegisterForm() {
    document.querySelector('.login-form').style.display = 'none';
    document.getElementById('registerForm').style.display = 'block';
    document.getElementById('loginModalTitle').textContent = '✨ 用户注册 ✨';
}

/**
 * 实时检查用户名是否已存在
 * 
 * <p>当用户名输入框失去焦点时，检查该用户名是否已被注册。</p>
 * <p>如果已存在，显示红色提示；否则显示绿色提示。</p>
 */
async function checkUsernameExists() {
    const username = document.getElementById('registerUsername').value.trim();
    const usernameCheckResult = document.getElementById('usernameCheckResult');
    
    if (!username) {
        if (usernameCheckResult) {
            usernameCheckResult.remove();
        }
        return;
    }

    try {
        const response = await fetch(`${API_BASE_URL}/auth/check-username?username=${encodeURIComponent(username)}`);
        const data = await response.json();
        
        // 移除之前的提示
        const existingResult = document.getElementById('usernameCheckResult');
        if (existingResult) {
            existingResult.remove();
        }

        // 创建新提示元素
        const resultElement = document.createElement('div');
        resultElement.id = 'usernameCheckResult';
        
        if (data.exists) {
            resultElement.className = 'username-check-error';
            resultElement.textContent = '✗ 该用户名已被使用';
        } else {
            resultElement.className = 'username-check-success';
            resultElement.textContent = '✓ 该用户名可用';
        }
        
        document.getElementById('registerUsername').parentNode.appendChild(resultElement);
    } catch (error) {
        console.error('检查用户名失败:', error);
    }
}

/**
 * 用户登录
 * 
 * <p>收集用户名和密码，调用后端登录API。</p>
 */
async function doLogin() {
    const username = document.getElementById('loginUsername').value.trim();
    const password = document.getElementById('loginPassword').value;

    // 验证输入
    if (!username) {
        showNotification('提示', '请输入用户名', 'warning');
        return;
    }
    if (!password) {
        showNotification('提示', '请输入密码', 'warning');
        return;
    }

    try {
        const response = await fetch(`${API_BASE_URL}/auth/login`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ username, password })
        });

        const data = await response.json();

        if (data.success) {
            handleLoginSuccess(data.userId, data.username, data.nickname);
        } else {
            showNotification('登录失败', data.message || '登录失败', 'error');
        }
    } catch (error) {
        console.error('登录失败:', error);
        showNotification('登录失败', '网络错误，请稍后重试', 'error');
    }
}

/**
 * 用户注册
 * 
 * <p>收集用户名、密码和昵称，调用后端注册API。</p>
 */
async function doRegister() {
    const username = document.getElementById('registerUsername').value.trim();
    const password = document.getElementById('registerPassword').value;
    const nickname = document.getElementById('registerNickname').value.trim();

    // 验证输入
    if (!username) {
        showNotification('提示', '请输入用户名', 'warning');
        return;
    }
    if (!password || password.length < 6) {
        showNotification('提示', '密码长度不能少于6位', 'warning');
        return;
    }

    try {
        const response = await fetch(`${API_BASE_URL}/auth/register`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ username, password, nickname })
        });

        const data = await response.json();

        if (data.success) {
            showNotification('注册成功', '注册成功，请登录', 'success');
            showLoginForm();
            document.getElementById('loginUsername').value = username;
            document.getElementById('loginPassword').value = '';
        } else {
            showNotification('注册失败', data.message || '注册失败', 'error');
        }
    } catch (error) {
        console.error('注册失败:', error);
        showNotification('注册失败', '网络错误，请稍后重试', 'error');
    }
}

/**
 * 处理登录成功
 * 
 * <p>登录成功后执行以下操作：</p>
 * <ol>
 *   <li>更新登录状态和用户信息</li>
 *   <li>保存登录信息到本地存储（包括登录时间戳）</li>
 *   <li>关闭登录弹窗并更新UI</li>
 *   <li>显示欢迎消息</li>
 *   <li>加载历史任务</li>
 *   <li>启动自动登出检查定时器</li>
 * </ol>
 * 
 * @param {string} userId 用户ID
 * @param {string} username 用户名
 * @param {string} nickname 昵称
 */
function handleLoginSuccess(userId, username, nickname) {
    // 保存当前时间作为登录时间戳
    loginTime = Date.now();
    
    // 更新登录状态和用户信息
    isLoggedIn = true;
    currentUserId = userId;
    currentUsername = username;
    
    // 保存到本地存储（包含登录时间戳）
    localStorage.setItem('userId', userId);
    localStorage.setItem('username', username);
    localStorage.setItem('loginTime', loginTime);
    if (nickname) {
        localStorage.setItem('nickname', nickname);
    }
    
    // 关闭登录弹窗
    document.getElementById('loginModalOverlay').classList.remove('active');
    
    // 更新UI：隐藏登录按钮，显示导航按钮
    document.getElementById('loginBtn').style.display = 'none';
    document.querySelector('.nav-buttons').style.display = 'flex';
    
    // 显示欢迎消息
    showNotification('登录成功', `欢迎回来，${nickname || username}！✨`, 'success');
    
    // 加载历史任务
    loadHistoryTasks();
    
    // 启动自动登出检查定时器
    startAutoLogoutChecker();
}

/**
 * 检查本地存储的登录状态
 * 
 * <p>页面刷新后尝试恢复登录状态：</p>
 * <ol>
 *   <li>从本地存储获取登录信息</li>
 *   <li>检查登录是否过期（超过2小时）</li>
 *   <li>如未过期，恢复登录状态；如已过期，执行自动登出</li>
 * </ol>
 */
function checkLoginStatus() {
    // 从本地存储获取登录信息
    const storedUserId = localStorage.getItem('userId');
    const storedUsername = localStorage.getItem('username');
    const storedLoginTime = localStorage.getItem('loginTime');
    
    // 如果有登录信息，检查是否过期
    if (storedUserId && storedUsername && storedLoginTime) {
        const now = Date.now();
        const loginTimeValue = parseInt(storedLoginTime, 10);
        
        // 检查登录是否超过2小时
        if (now - loginTimeValue > LOGIN_EXPIRE_TIME) {
            // 登录已过期，执行自动登出
            console.log('登录已过期，执行自动登出');
            performLogout();
            return;
        }
        
        // 登录未过期，恢复登录状态
        isLoggedIn = true;
        currentUserId = storedUserId;
        currentUsername = storedUsername;
        loginTime = loginTimeValue;
        
        // 更新UI：隐藏登录按钮，显示导航按钮
        document.getElementById('loginBtn').style.display = 'none';
        document.querySelector('.nav-buttons').style.display = 'flex';
        
        // 加载历史任务
        loadHistoryTasks();
    }
}

/**
 * 执行登出操作（内部方法）
 * 
 * <p>执行实际的登出操作，包括：</p>
 * <ol>
 *   <li>清除登录状态和变量</li>
 *   <li>清除本地存储的登录信息</li>
 *   <li>停止自动登出检查定时器</li>
 *   <li>更新UI显示</li>
 *   <li>清空历史任务显示</li>
 *   <li>显示登出提示</li>
 * </ol>
 * 
 * @param {boolean} showNotification 是否显示登出提示
 */
function performLogout(showNotification = true) {
    // 清除登录状态和变量
    isLoggedIn = false;
    currentUserId = null;
    currentUsername = null;
    loginTime = null;
    
    // 停止自动登出检查定时器
    stopAutoLogoutChecker();
    
    // 清除本地存储的登录信息
    localStorage.removeItem('userId');
    localStorage.removeItem('username');
    localStorage.removeItem('nickname');
    localStorage.removeItem('loginTime');
    
    // 更新UI：显示登录按钮，隐藏导航按钮
    document.getElementById('loginBtn').style.display = 'block';
    document.querySelector('.nav-buttons').style.display = 'none';
    
    // 显示工具页面
    showToolsSection();
    
    // 清空历史任务显示
    document.getElementById('historyContainer').innerHTML = `
        <div class="empty-state">
            <svg viewBox="0 0 24 24" fill="none" stroke="#94a3b8" stroke-width="1.5" width="64" height="64">
                <path d="M12 20v-6m0 0l-3 3m3-3l3 3"/>
                <path d="M4 6h16M4 10h16M4 14h16M4 18h16"/>
            </svg>
            <p>暂无任务记录</p>
        </div>
    `;
    
    // 显示登出提示
    if (showNotification) {
        showNotification('登出成功', '已安全退出登录', 'success');
    }
}

/**
 * 用户主动登出
 * 
 * <p>用户点击登出按钮时调用，显示确认提示后执行登出。</p>
 */
function logout() {
    if (confirm('确定要退出登录吗？')) {
        performLogout(true);
    }
}

/**
 * 启动自动登出检查定时器
 * 
 * <p>每隔一定时间（1分钟）检查一次登录是否过期。</p>
 * <p>如果登录已过期，自动执行登出操作。</p>
 */
function startAutoLogoutChecker() {
    // 停止已存在的定时器
    stopAutoLogoutChecker();
    
    // 启动新的定时器
    autoLogoutTimer = setInterval(() => {
        // 如果未登录，不检查
        if (!isLoggedIn || !loginTime) {
            return;
        }
        
        // 检查登录是否过期
        const now = Date.now();
        if (now - loginTime > LOGIN_EXPIRE_TIME) {
            console.log('登录已过期，执行自动登出');
            showNotification('登录已过期', '登录已超过2小时，请重新登录', 'warning');
            performLogout(false);
        }
    }, AUTO_CHECK_INTERVAL);
}

/**
 * 停止自动登出检查定时器
 */
function stopAutoLogoutChecker() {
    if (autoLogoutTimer) {
        clearInterval(autoLogoutTimer);
        autoLogoutTimer = null;
    }
}

/**
 * 初始化导航按钮
 * 
 * <p>为导航按钮添加点击事件，切换不同页面。</p>
 */
function initNavigation() {
    const toolsBtn = document.getElementById('toolsBtn');
    const advancedBtn = document.getElementById('advancedBtn');
    const userBtn = document.getElementById('userBtn');

    toolsBtn.addEventListener('click', showToolsSection);
    advancedBtn.addEventListener('click', showAdvancedSection);
    userBtn.addEventListener('click', showUserSection);
}

/**
 * 显示工具页面
 */
function showToolsSection() {
    document.getElementById('tools').style.display = 'block';
    document.getElementById('advanced').style.display = 'none';
    document.getElementById('history').style.display = 'none';
    
    // 更新按钮选中状态
    document.querySelectorAll('.nav-btn').forEach(btn => btn.classList.remove('active'));
    document.getElementById('toolsBtn').classList.add('active');
}

/**
 * 显示高级工具页面
 */
function showAdvancedSection() {
    document.getElementById('tools').style.display = 'none';
    document.getElementById('advanced').style.display = 'block';
    document.getElementById('history').style.display = 'none';
    
    // 更新按钮选中状态
    document.querySelectorAll('.nav-btn').forEach(btn => btn.classList.remove('active'));
    document.getElementById('advancedBtn').classList.add('active');
}

/**
 * 显示用户页面（历史任务）
 */
function showUserSection() {
    if (!isLoggedIn) {
        showNotification('提示', '请先登录', 'warning');
        document.getElementById('loginBtn').click();
        return;
    }
    
    document.getElementById('tools').style.display = 'none';
    document.getElementById('advanced').style.display = 'none';
    document.getElementById('history').style.display = 'block';
    
    // 更新按钮选中状态
    document.querySelectorAll('.nav-btn').forEach(btn => btn.classList.remove('active'));
    document.getElementById('userBtn').classList.add('active');
    
    // 加载历史任务
    loadHistoryTasks();
}

/**
 * 打开任务提交弹窗
 * 
 * @param {string} toolId 工具ID
 */
function openModal(toolId) {
    currentTool = toolId;
    const toolInfo = tools[toolId];
    
    // 设置弹窗标题
    document.getElementById('modalTitle').textContent = toolInfo.name;
    
    const textSection = document.getElementById('textSection');
    const uploadSection = document.getElementById('uploadSection');
    const uploadLabel = uploadSection.querySelector('label');
    const uploadText = uploadSection.querySelector('.upload-area p');
    const uploadHint = uploadSection.querySelector('.upload-hint');
    const uploadIcon = uploadSection.querySelector('.upload-icon svg path');
    const inputText = document.getElementById('inputText');
    
    // 显示/隐藏文字输入区域
    textSection.style.display = (toolInfo.needText && !toolInfo.needDoc) ? 'block' : 'none';
    // 显示/隐藏上传区域
    uploadSection.style.display = (toolInfo.needImage || toolInfo.needVideo || toolInfo.needDoc) ? 'block' : 'none';
    
    // 设置占位文字
    if (toolId === 'text-to-speech') {
        inputText.placeholder = '请输入待转为语音的文字';
    } else {
        inputText.placeholder = '请输入描述文字...';
    }
    
    // 设置上传区域内容
    if (toolInfo.needImage && !toolInfo.needVideo && !toolInfo.needDoc) {
        uploadLabel.textContent = '上传图片';
        uploadText.textContent = '点击或拖拽上传图片';
        uploadHint.textContent = '支持jpg、png格式';
        uploadIcon.innerHTML = '<path d="M5 15h4v6h6v-6h4l-7-7-7 7z"/>';
    } else if (toolInfo.needVideo) {
        uploadLabel.textContent = '上传视频';
        uploadText.textContent = '点击或拖拽上传视频';
        uploadHint.textContent = '支持mp4、avi格式';
        uploadIcon.innerHTML = '<path d="M5 15h4v6h6v-6h4l-7-7-7 7z"/>';
    } else if (toolInfo.needDoc) {
        uploadLabel.textContent = '上传剧本';
        uploadText.textContent = '点击或拖拽上传Word文档';
        uploadHint.textContent = '支持doc、docx格式';
        uploadIcon.innerHTML = '<path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 7V3.5L18.5 9H13z"/>';
    } else {
        uploadLabel.textContent = '上传文件';
        uploadText.textContent = '点击或拖拽上传文件';
        uploadHint.textContent = '支持图片、视频格式';
        uploadIcon.innerHTML = '<path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/>';
    }
    
    // 设置文件选择器接受的文件类型
    const fileInput = document.getElementById('fileInput');
    if (toolInfo.needDoc) {
        fileInput.accept = '.doc,.docx,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    } else if (toolInfo.needImage && !toolInfo.needVideo) {
        fileInput.accept = 'image/*';
    } else if (toolInfo.needVideo) {
        fileInput.accept = 'video/*';
    } else {
        fileInput.accept = 'image/*,video/*';
    }
    
    // 清空表单
    inputText.value = '';
    uploadedFiles = [];
    document.getElementById('uploadedFiles').innerHTML = '';
    
    // 显示弹窗
    document.getElementById('modalOverlay').classList.add('active');
}

/**
 * 关闭任务提交弹窗
 */
function closeModal() {
    document.getElementById('modalOverlay').classList.remove('active');
    currentTool = null;
    uploadedFiles = [];
}

/**
 * 处理上传的文件
 * 
 * @param {File[]} files 文件数组
 */
function handleFiles(files) {
    const toolInfo = tools[currentTool];
    
    files.forEach(file => {
        const isValid = validateFile(file);
        if (isValid) {
            addFile(file);
        } else {
            showNotification('错误', `文件 "${file.name}" 格式不支持`, 'error');
        }
    });
}

/**
 * 验证文件格式
 * 
 * @param {File} file 文件对象
 * @returns {boolean} 是否有效
 */
function validateFile(file) {
    const toolInfo = tools[currentTool];
    
    if (toolInfo.needDoc) {
        return file.type === 'application/msword' || 
               file.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    }
    
    if (toolInfo.needImage) {
        return file.type.startsWith('image/');
    }
    
    if (toolInfo.needVideo) {
        return file.type.startsWith('video/');
    }
    
    return true;
}

/**
 * 添加文件到上传列表
 * 
 * @param {File} file 文件对象
 */
function addFile(file) {
    uploadedFiles.push(file);
    
    const uploadedFilesContainer = document.getElementById('uploadedFiles');
    const fileElement = document.createElement('div');
    fileElement.className = 'uploaded-file';
    fileElement.dataset.index = uploadedFiles.length - 1;
    
    const reader = new FileReader();
    reader.onload = (e) => {
        let preview = '';
        if (file.type.startsWith('image/')) {
            preview = `<img src="${e.target.result}" alt="${file.name}">`;
        } else if (file.type.startsWith('video/')) {
            preview = `<video src="${e.target.result}" controls></video>`;
        } else {
            preview = `<div class="file-icon">📄</div>`;
        }
        
        fileElement.innerHTML = `
            ${preview}
            <span>${file.name}</span>
            <button class="remove-file" data-index="${uploadedFiles.length - 1}">×</button>
        `;
        
        uploadedFilesContainer.appendChild(fileElement);
        
        // 添加删除按钮事件
        fileElement.querySelector('.remove-file').addEventListener('click', (e) => {
            const index = parseInt(e.target.dataset.index);
            uploadedFiles.splice(index, 1);
            e.target.parentElement.remove();
            // 更新剩余文件的索引
            document.querySelectorAll('.uploaded-file').forEach((el, i) => {
                el.dataset.index = i;
                el.querySelector('.remove-file').dataset.index = i;
            });
        });
    };
    
    reader.readAsDataURL(file);
}

/**
 * 提交任务到后端
 * 
 * <p>收集表单数据，构建FormData，调用后端API提交任务。</p>
 */
async function submitTaskToBackend() {
    const toolInfo = tools[currentTool];
    const inputText = document.getElementById('inputText');
    const description = toolInfo.needText ? inputText.value.trim() : '';
    
    // 验证输入
    if ((toolInfo.needText || toolInfo.needDoc) && !description && uploadedFiles.length === 0) {
        showNotification('提示', '请输入描述文字或上传文件', 'warning');
        return;
    }
    
    if ((toolInfo.needImage || toolInfo.needVideo || toolInfo.needDoc) && uploadedFiles.length === 0) {
        showNotification('提示', '请上传文件', 'warning');
        return;
    }
    
    // 构建FormData
    const formData = new FormData();
    formData.append('userId', currentUserId);
    formData.append('type', currentTool);
    if (description) {
        formData.append('description', description);
    }
    uploadedFiles.forEach((file, index) => {
        formData.append(`file`, file);
    });
    
    // 显示加载动画
    showProgress();
    
    try {
        const response = await fetch(`${API_BASE_URL}/tasks/submit`, {
            method: 'POST',
            body: formData
        });
        
        const data = await response.json();
        
        if (data.success) {
            showNotification('任务已提交', `任务ID: ${data.taskId}`, 'success');
            closeModal();
            
            // 模拟任务处理（实际应用中应轮询后端状态）
            simulateTaskProgress(data.taskId);
        } else {
            showNotification('提交失败', data.message || '未知错误', 'error');
        }
    } catch (error) {
        console.error('提交任务失败:', error);
        showNotification('提交失败', '网络错误', 'error');
    } finally {
        hideProgress();
    }
}

/**
 * 显示任务进度弹窗
 */
function showProgress() {
    document.getElementById('progressOverlay').classList.add('active');
}

/**
 * 隐藏任务进度弹窗
 */
function hideProgress() {
    document.getElementById('progressOverlay').classList.remove('active');
}

/**
 * 模拟任务进度（演示用）
 * 
 * @param {string} taskId 任务ID
 */
function simulateTaskProgress(taskId) {
    const progressText = document.getElementById('progressText');
    
    // 随机延迟模拟任务处理，2-5秒后完成
    const delay = 2000 + Math.random() * 3000;
    
    setTimeout(() => {
        progressText.textContent = '任务完成！';
        setTimeout(() => {
            hideProgress();
            loadHistoryTasks();
        }, 500);
    }, delay);
}

/**
 * 加载历史任务
 * 
 * <p>从后端获取当前用户的任务列表。</p>
 */
async function loadHistoryTasks() {
    if (!isLoggedIn || !currentUserId) return;
    
    try {
        const response = await fetch(`${API_BASE_URL}/tasks/user/${currentUserId}`);
        const tasks = await response.json();
        renderHistoryTasks(tasks);
    } catch (error) {
        console.error('加载任务失败:', error);
        // 加载模拟数据
        loadMockTasks();
    }
}

/**
 * 加载模拟任务数据（演示用）
 */
function loadMockTasks() {
    const now = new Date();
    const mockTasks = [
        {
            taskId: 'mock001',
            userId: currentUserId,
            type: 'text-to-image',
            description: '一只可爱的猫咪在草地上玩耍',
            status: '执行成功',
            progress: 100,
            submitTime: new Date(now.getTime() - 2 * 60 * 60 * 1000).toISOString(),
            downloadPath: '/download/mock001.jpg'
        },
        {
            taskId: 'mock002',
            userId: currentUserId,
            type: 'text-to-video',
            description: '一段展示未来城市的动画视频',
            status: '执行中',
            progress: 65,
            submitTime: new Date(now.getTime() - 30 * 60 * 1000).toISOString(),
            downloadPath: null
        },
        {
            taskId: 'mock003',
            userId: currentUserId,
            type: 'image-to-video',
            description: '将风景图片转化为视频',
            status: '排队中',
            progress: 0,
            submitTime: new Date(now.getTime() - 10 * 60 * 1000).toISOString(),
            downloadPath: null
        },
        {
            taskId: 'mock004',
            userId: currentUserId,
            type: 'video-remove-subtitle',
            description: '',
            status: '执行成功',
            progress: 100,
            submitTime: new Date(now.getTime() - 4 * 60 * 60 * 1000).toISOString(),
            downloadPath: '/download/mock004.mp4'
        },
        {
            taskId: 'mock005',
            userId: currentUserId,
            type: 'text-to-speech',
            description: '欢迎使用AI魔法工坊',
            status: '执行成功',
            progress: 100,
            submitTime: new Date(now.getTime() - 6 * 60 * 60 * 1000).toISOString(),
            downloadPath: '/download/mock005.mp3'
        },
        
        {
            taskId: 'mock007',
            userId: currentUserId,
            type: 'face-consistency',
            description: '人物迁移到古代场景',
            status: '执行成功',
            progress: 100,
            submitTime: new Date(now.getTime() - 10 * 60 * 60 * 1000).toISOString(),
            downloadPath: '/download/mock007.jpg'
        },
        {
            taskId: 'mock008',
            userId: currentUserId,
            type: 'text-to-video-audio',
            description: '生成带语音解说的风景视频',
            status: '执行中',
            progress: 45,
            submitTime: new Date(now.getTime() - 45 * 60 * 1000).toISOString(),
            downloadPath: null
        },
        {
            taskId: 'mock009',
            userId: currentUserId,
            type: 'image-to-video-audio',
            description: '图片转视频并添加背景音乐',
            status: '排队中',
            progress: 0,
            submitTime: new Date(now.getTime() - 5 * 60 * 1000).toISOString(),
            downloadPath: null
        }
    ];
    
    renderHistoryTasks(mockTasks);
}

/**
 * 渲染历史任务列表
 * 
 * @param {Array} tasks 任务数组
 */
function renderHistoryTasks(tasks) {
    const container = document.getElementById('historyContainer');
    
    if (!tasks || tasks.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <svg viewBox="0 0 24 24" fill="none" stroke="#94a3b8" stroke-width="1.5" width="64" height="64">
                    <path d="M12 20v-6m0 0l-3 3m3-3l3 3"/>
                    <path d="M4 6h16M4 10h16M4 14h16M4 18h16"/>
                </svg>
                <p>暂无任务记录</p>
            </div>
        `;
        return;
    }
    
    const now = new Date();
    container.innerHTML = tasks.map(task => {
        const submitTime = new Date(task.submitTime);
        const hoursAgo = Math.floor((now - submitTime) / (1000 * 60 * 60));
        const expiresIn = Math.max(0, 24 - hoursAgo);
        
        let statusIcon = '';
        let statusClass = '';
        if (task.status === '执行成功') {
            statusIcon = '✓';
            statusClass = 'status-success';
        } else if (task.status === '执行中') {
            statusIcon = '◐';
            statusClass = 'status-processing';
        } else if (task.status === '排队中') {
            statusIcon = '○';
            statusClass = 'status-pending';
        } else {
            statusIcon = '✗';
            statusClass = 'status-failed';
        }
        
        const toolName = tools[task.type]?.name || task.type;
        
        return `
            <div class="task-item">
                <div class="task-status ${statusClass}">${statusIcon}</div>
                <div class="task-info">
                    <h4>${toolName}</h4>
                    <p>${task.description || '无描述'}</p>
                    <div class="task-meta">
                        <span>提交于 ${formatTime(submitTime)}</span>
                        ${expiresIn < 10 && expiresIn > 0 ? `<span class="expire-warning">剩余${expiresIn}小时过期</span>` : ''}
                    </div>
                </div>
                <div class="task-actions">
                    ${task.status === '执行成功' && task.downloadPath ? `
                        <button class="download-btn" onclick="downloadTask('${task.taskId}')">下载</button>
                    ` : ''}
                    <button class="delete-btn" onclick="deleteTask('${task.taskId}')">删除</button>
                </div>
            </div>
        `;
    }).join('');
    
    // 轮询更新进行中的任务
    startTaskProgressPoll(tasks.filter(t => t.status === '执行中'));
}

/**
 * 格式化时间
 * 
 * @param {Date} date 日期对象
 * @returns {string} 格式化的时间字符串
 */
function formatTime(date) {
    const pad = (n) => n.toString().padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 下载任务结果
 * 
 * @param {string} taskId 任务ID
 */
function downloadTask(taskId) {
    showNotification('提示', '准备下载...', 'info');
    
    console.log('开始下载任务:', taskId);
    
    fetch(`${API_BASE_URL}/tasks/${taskId}`)
        .then(response => {
            console.log('获取任务信息响应状态:', response.status);
            if (!response.ok) {
                throw new Error('获取任务信息失败');
            }
            return response.json();
        })
        .then(task => {
            console.log('获取到的任务信息:', task);
            
            if (task.downloadPath) {
                console.log('下载路径:', task.downloadPath);
                const downloadUrl = `${API_BASE_URL}/tasks/${taskId}/download`;
                console.log('下载URL:', downloadUrl);
                
                const link = document.createElement('a');
                link.href = downloadUrl;
                link.download = task.downloadPath.split('/').pop();
                document.body.appendChild(link);
                link.click();
                document.body.removeChild(link);
                showNotification('成功', '下载已开始', 'success');
            } else {
                console.log('downloadPath为空');
                showNotification('错误', '暂无下载文件', 'error');
            }
        })
        .catch(error => {
            console.error('下载失败:', error);
            showNotification('错误', '下载失败: ' + error.message, 'error');
        });
}

/**
 * 删除任务
 * 
 * @param {string} taskId 任务ID
 */
function deleteTask(taskId) {
    if (confirm('确定删除此任务？')) {
        // 实际应调用后端删除接口
        showNotification('提示', '任务已删除', 'success');
        loadHistoryTasks();
    }
}

/**
 * 轮询任务进度
 * 
 * @param {Array} runningTasks 进行中的任务
 */
function startTaskProgressPoll(runningTasks) {
    if (runningTasks.length === 0) return;
    
    const interval = setInterval(async () => {
        for (const task of runningTasks) {
            try {
                const response = await fetch(`${API_BASE_URL}/tasks/${task.taskId}`);
                const updatedTask = await response.json();
                
                if (updatedTask.status === '执行成功' || updatedTask.status === '执行失败') {
                    loadHistoryTasks();
                    clearInterval(interval);
                    break;
                }
            } catch (error) {
                console.error('轮询任务进度失败:', error);
            }
        }
    }, 3000);
}

/**
 * 显示通知
 * 
 * @param {string} title 标题
 * @param {string} message 消息内容
 * @param {string} type 类型：success, error, warning, info
 */
function showNotification(title, message, type = 'info') {
    const container = document.getElementById('notificationContainer');
    const notification = document.createElement('div');
    notification.className = `notification notification-${type}`;
    
    let icon = '';
    switch (type) {
        case 'success': icon = '✓'; break;
        case 'error': icon = '✗'; break;
        case 'warning': icon = '⚠'; break;
        default: icon = 'ℹ';
    }
    
    notification.innerHTML = `
        <div class="notification-icon">${icon}</div>
        <div class="notification-content">
            <strong>${title}</strong>
            <p>${message}</p>
        </div>
        <button class="notification-close">×</button>
    `;
    
    container.appendChild(notification);
    
    // 点击关闭按钮
    notification.querySelector('.notification-close').addEventListener('click', () => {
        notification.remove();
    });
    
    // 自动消失
    setTimeout(() => {
        notification.classList.add('fade-out');
        setTimeout(() => notification.remove(), 300);
    }, 4000);
}