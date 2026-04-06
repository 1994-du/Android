// H5端调用示例代码（JSONObject格式）

/**
 * 处理相机和相册返回结果
 * 现在返回的是JSONObject格式，包含以下字段：
 * - callbackId: 回调ID
 * - image: Base64格式的图片数据
 * - type: 类型（"camera"或"gallery"）
 */
window.handlePhotoResult = function(result) {
    console.log('图片返回结果:', result);
    
    // 提取数据
    const { callbackId, image, type } = result;
    
    console.log('回调ID:', callbackId);
    console.log('图片类型:', type); // "camera" 或 "gallery"
    console.log('Base64图片长度:', image ? image.length : 0);
    
    // 显示图片
    if (image) {
        const img = document.createElement('img');
        img.src = image;
        img.style.maxWidth = '100%';
        img.style.height = 'auto';
        
        // 根据类型显示不同的标题
        const container = document.createElement('div');
        container.style.margin = '10px 0';
        container.style.padding = '10px';
        container.style.border = '1px solid #ddd';
        
        const title = document.createElement('h3');
        title.textContent = type === 'camera' ? '相机拍摄' : '相册选择';
        container.appendChild(title);
        container.appendChild(img);
        
        document.body.appendChild(container);
    }
};

/**
 * 处理错误
 * 现在返回的是JSONObject格式，包含以下字段：
 * - callbackId: 回调ID
 * - error: 错误信息
 */
window.handlePhotoError = function(errorObj) {
    console.error('图片操作错误:', errorObj);
    
    const { callbackId, error } = errorObj;
    console.error('回调ID:', callbackId);
    console.error('错误信息:', error);
    
    // 显示错误提示
    const errorElement = document.createElement('div');
    errorElement.style.color = 'red';
    errorElement.style.margin = '10px 0';
    errorElement.textContent = `错误: ${error}`;
    document.body.appendChild(errorElement);
};

/**
 * 打开相机
 * @param {string} callbackId - 回调ID
 */
function openCamera(callbackId = 'default') {
    if (window.AndroidPhoto) {
        window.AndroidPhoto.openCamera(callbackId);
    } else {
        console.warn('AndroidPhoto interface not available');
    }
}

/**
 * 打开相册
 * @param {string} callbackId - 回调ID
 */
function openGallery(callbackId = 'default') {
    if (window.AndroidPhoto) {
        window.AndroidPhoto.openGallery(callbackId);
    } else {
        console.warn('AndroidPhoto interface not available');
    }
}

// 示例：绑定按钮事件
window.addEventListener('DOMContentLoaded', function() {
    // 相机按钮
    const cameraBtn = document.getElementById('camera-btn');
    if (cameraBtn) {
        cameraBtn.addEventListener('click', function() {
            openCamera('camera_123');
        });
    }
    
    // 相册按钮
    const galleryBtn = document.getElementById('gallery-btn');
    if (galleryBtn) {
        galleryBtn.addEventListener('click', function() {
            openGallery('gallery_456');
        });
    }
    
    // 测试按钮
    const testBtn = document.getElementById('test-btn');
    if (testBtn) {
        testBtn.addEventListener('click', function() {
            console.log('测试功能:');
            console.log('AndroidPhoto available:', !!window.AndroidPhoto);
            console.log('AndroidChat available:', !!window.AndroidChat);
        });
    }
});

// 示例：完整的使用流程
function initPhotoUpload() {
    console.log('初始化图片上传功能');
    
    // 可以在这里设置默认的回调处理
    window.handlePhotoResult = function(result) {
        console.log('图片上传结果:', result);
        
        // 这里可以添加上传到服务器的逻辑
        if (result.image) {
            console.log('准备上传图片，大小:', (result.image.length * 3) / 4 / 1024, 'KB');
            // uploadToServer(result.image);
        }
    };
    
    window.handlePhotoError = function(errorObj) {
        console.error('图片操作失败:', errorObj.error);
        // 显示错误提示
        alert('操作失败: ' + errorObj.error);
    };
}

// 调用初始化函数
if (typeof window.initPhotoUpload === 'undefined') {
    window.initPhotoUpload = initPhotoUpload;
}
