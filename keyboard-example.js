// H5端调用示例代码

/**
 * 监听输入法状态变化
 * 当输入法弹出或收起时，Android会自动调用这个函数
 */
window.handleKeyboardStatus = function(visible, height) {
    console.log('输入法状态变化:', {
        visible: visible,
        height: height // 单位：dp
    });
    
    // 在这里处理输入法状态变化
    if (visible) {
        console.log('输入法弹出，高度:', height, 'dp');
        // 可以调整页面布局，比如将输入框上移
        adjustLayoutForKeyboard(height);
    } else {
        console.log('输入法收起');
        // 恢复页面布局
        restoreLayout();
    }
};

/**
 * 处理获取输入法高度的回调
 * 当H5调用getKeyboardHeight()时，Android会调用这个函数
 */
window.handleKeyboardHeight = function(visible, height) {
    console.log('获取到输入法状态:', {
        visible: visible,
        height: height // 单位：dp
    });
    
    // 在这里处理返回的输入法高度
    if (visible) {
        console.log('当前输入法高度:', height, 'dp');
        // 可以根据高度调整布局
        adjustLayoutForKeyboard(height);
    }
};

/**
 * 主动获取输入法高度
 * 可以在需要的时候调用
 */
function getKeyboardHeight() {
    if (window.AndroidChat) {
        window.AndroidChat.getKeyboardHeight();
    } else {
        console.warn('AndroidChat interface not available');
    }
}

/**
 * 根据输入法高度调整布局
 * @param {number} keyboardHeight - 输入法高度（dp）
 */
function adjustLayoutForKeyboard(keyboardHeight) {
    // 示例：调整输入框容器的底部边距
    const inputContainer = document.getElementById('input-container');
    if (inputContainer) {
        inputContainer.style.marginBottom = keyboardHeight + 'px';
    }
    
    // 示例：滚动页面，确保输入框可见
    const activeInput = document.activeElement;
    if (activeInput && activeInput.tagName === 'INPUT') {
        activeInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
}

/**
 * 恢复布局
 */
function restoreLayout() {
    // 示例：恢复输入框容器的底部边距
    const inputContainer = document.getElementById('input-container');
    if (inputContainer) {
        inputContainer.style.marginBottom = '0px';
    }
}

// 示例：在页面加载时注册事件
window.addEventListener('DOMContentLoaded', function() {
    // 绑定输入框焦点事件
    const inputElements = document.querySelectorAll('input, textarea');
    inputElements.forEach(input => {
        input.addEventListener('focus', function() {
            // 输入框获取焦点时，主动获取输入法高度
            setTimeout(getKeyboardHeight, 300);
        });
    });
    
    // 绑定按钮事件
    const getHeightBtn = document.getElementById('get-keyboard-height');
    if (getHeightBtn) {
        getHeightBtn.addEventListener('click', getKeyboardHeight);
    }
});

// 示例：在聊天页面的使用
function initChatPage() {
    // 监听输入法状态
    window.handleKeyboardStatus = function(visible, height) {
        if (visible) {
            // 输入法弹出，调整聊天输入区域
            const chatInputArea = document.getElementById('chat-input-area');
            if (chatInputArea) {
                chatInputArea.style.transform = `translateY(-${height}px)`;
            }
        } else {
            // 输入法收起，恢复聊天输入区域
            const chatInputArea = document.getElementById('chat-input-area');
            if (chatInputArea) {
                chatInputArea.style.transform = 'translateY(0)';
            }
        }
    };
    
    // 监听发送按钮点击
    const sendBtn = document.getElementById('send-btn');
    if (sendBtn) {
        sendBtn.addEventListener('click', function() {
            // 发送消息后，可能需要处理输入法状态
            getKeyboardHeight();
        });
    }
}

// 调用初始化函数
if (typeof window.initChatPage === 'undefined') {
    window.initChatPage = initChatPage;
}
