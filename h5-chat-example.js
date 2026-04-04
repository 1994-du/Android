// H5端调用示例代码

/**
 * 显示消息通知
 * @param {Object} messageData - 消息数据
 * @param {string} messageData.title - 通知标题（通常是发送者名称）
 * @param {string} messageData.message - 消息内容
 * @param {string} messageData.senderId - 发送者ID
 * @param {string} messageData.senderName - 发送者名称
 * @param {string} messageData.conversationId - 会话ID
 * @param {number} messageData.badgeCount - 未读消息数量
 */
function showChatNotification(messageData) {
    if (window.AndroidChat) {
        window.AndroidChat.showNotification(
            messageData.title,
            messageData.message,
            messageData.senderId || null,
            messageData.senderName || null,
            messageData.conversationId || null,
            messageData.badgeCount || 1
        );
    } else {
        console.warn('AndroidChat interface not available');
    }
}

/**
 * 取消指定会话的通知
 * @param {string} conversationId - 会话ID
 */
function cancelChatNotification(conversationId) {
    if (window.AndroidChat) {
        window.AndroidChat.cancelNotification(conversationId);
    }
}

/**
 * 取消所有通知
 */
function cancelAllNotifications() {
    if (window.AndroidChat) {
        window.AndroidChat.cancelAllNotifications();
    }
}

/**
 * 更新应用图标角标数量
 * @param {number} count - 未读消息总数
 */
function updateBadgeCount(count) {
    if (window.AndroidChat) {
        window.AndroidChat.updateBadgeCount(count);
    }
}

/**
 * 处理通知点击事件
 * 当用户点击通知时，Android会调用这个函数
 */
window.handleNotificationClick = function(data) {
    console.log('Notification clicked:', data);
    
    // 跳转到对应的聊天页面
    if (data.conversationId) {
        // 例如：跳转到聊天详情页
        window.location.href = `/chat/${data.conversationId}`;
        
        // 或者使用你的路由框架
        // router.push({ name: 'chat', params: { id: data.conversationId } });
    }
};

// 使用示例

// 示例1：收到新消息时显示通知
function onReceiveMessage(message) {
    showChatNotification({
        title: message.senderName,
        message: message.content,
        senderId: message.senderId,
        senderName: message.senderName,
        conversationId: message.conversationId,
        badgeCount: message.unreadCount
    });
}

// 示例2：用户打开聊天页面时取消通知
function onOpenChat(conversationId) {
    cancelChatNotification(conversationId);
}

// 示例3：用户查看所有消息后清除所有通知
function onMarkAllAsRead() {
    cancelAllNotifications();
    updateBadgeCount(0);
}

// 示例4：WebSocket接收到消息的处理
const ws = new WebSocket('wss://your-websocket-url');

ws.onmessage = function(event) {
    const data = JSON.parse(event.data);
    
    if (data.type === 'new_message') {
        // 显示通知
        onReceiveMessage(data.message);
        
        // 更新UI
        updateChatUI(data.message);
    }
};

// 示例5：轮询方式获取新消息
async function pollNewMessages() {
    try {
        const response = await fetch('/api/messages/new');
        const messages = await response.json();
        
        messages.forEach(message => {
            onReceiveMessage(message);
        });
    } catch (error) {
        console.error('Failed to poll messages:', error);
    }
}

// 每隔30秒轮询一次
setInterval(pollNewMessages, 30000);
