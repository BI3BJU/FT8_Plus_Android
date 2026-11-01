import os

# 配置你的项目 res 路径
RES_DIR = r"C:\Users\Administrator\AndroidStudioProjects\FT8_plus\app\src\main\res"
ANYDPI_DIR = os.path.join(RES_DIR, "mipmap-anydpi-v26")

def force_fix():
    print("🔧 开始强制修复自适应图标配置...\n" + "="*50)
    
    if not os.path.exists(ANYDPI_DIR):
        os.makedirs(ANYDPI_DIR)
        print(f"📁 创建目录: {ANYDPI_DIR}")

    # 这是全新的自适应图标配置：背景透明，前景使用你的 logo.png
    xml_content = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/transparent"/>
    <foreground android:drawable="@drawable/logo"/>
</adaptive-icon>
"""
    
    xml_files = ["ic_launcher.xml", "ic_launcher_round.xml"]
    
    for xml_name in xml_files:
        xml_path = os.path.join(ANYDPI_DIR, xml_name)
        with open(xml_path, 'w', encoding='utf-8') as f:
            f.write(xml_content)
        print(f"✅ 已强制覆盖: mipmap-anydpi-v26/{xml_name}")
        print("   └─ 已移除默认机器人，前景改为 @drawable/logo，背景设为透明")

    print("\n" + "="*50)
    print("🎉 修复完成！接下来请回到 Android Studio：")
    print("1. 点击菜单栏 Build -> Clean Project (非常重要)")
    print("2. 重新 Run 运行 App")
    print("3. 如果手机桌面还是旧图标，请长按 App 图标 -> 应用信息 -> 存储 -> 清除缓存。")

if __name__ == "__main__":
    force_fix()