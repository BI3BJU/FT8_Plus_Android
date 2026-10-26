import os
import re

# 配置你的项目 res 路径
RES_DIR = r"C:\Users\Administrator\AndroidStudioProjects\FT8_plus\app\src\main\res"
MANIFEST_PATH = r"C:\Users\Administrator\AndroidStudioProjects\FT8_plus\app\src\main\AndroidManifest.xml"

def check_fixed_icons():
    print(f"🔍 开始复查 FT8_plus 图标配置...")
    print(f"📂 目标目录: {RES_DIR}\n" + "="*55)
    
    if not os.path.exists(RES_DIR):
        print("❌ 错误：找不到 res 目录！")
        return

    is_perfect = True

    # 1. 检查根目录是否有残留图片
    print("📄 1. 检查 res/ 根目录整洁度")
    root_files = [f for f in os.listdir(RES_DIR) if f.endswith(('.png', '.jpg', '.webp'))]
    if root_files:
        print(f"  ❌ [错误] res/ 根目录下仍然有图片文件: {', '.join(root_files)}")
        print("     请将它们移入 drawable 或 mipmap 文件夹中。")
        is_perfect = False
    else:
        print("  ✅ 根目录干净，没有违规图片。")
    print("-" * 55)

    # 2. 检查 drawable/logo.png
    print("🖼️ 2. 检查核心原图 (drawable/logo.png)")
    logo_path = os.path.join(RES_DIR, "drawable", "logo.png")
    if os.path.exists(logo_path):
        print("  ✅ 找到 drawable/logo.png")
    else:
        print("  ❌ [错误] 找不到 drawable/logo.png，请确认文件是否存在。")
        is_perfect = False
    print("-" * 55)

    # 3. 检查自适应图标 XML 配置
    print("⚙️ 3. 检查自适应图标配置 (mipmap-anydpi-v26)")
    anydpi_dir = os.path.join(RES_DIR, "mipmap-anydpi-v26")
    xml_files = ["ic_launcher.xml", "ic_launcher_round.xml"]
    
    for xml_name in xml_files:
        xml_path = os.path.join(anydpi_dir, xml_name)
        if os.path.exists(xml_path):
            with open(xml_path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # 检查前景
            if '@drawable/logo' in content:
                print(f"  ✅ {xml_name}: 前景层配置正确 (@drawable/logo)")
            elif '@mipmap/ic_launcher' in content:
                print(f"  ❌ {xml_name}: 发现循环引用！请改为 @drawable/logo")
                is_perfect = False
            else:
                print(f"  ⚠️ {xml_name}: 前景层引用了未知资源，请检查。")
                is_perfect = False

            # 检查背景
            if '@android:color/transparent' in content:
                print(f"     └─ 背景层配置正确 (透明背景)")
            else:
                print(f"     ⚠️ 背景层不是透明背景，可能会遮挡圆角效果。")
        else:
            print(f"  ❌ [错误] 找不到 {xml_name}")
            is_perfect = False
    print("-" * 55)

    # 4. 检查传统 mipmap 图标
    print("🗂️ 4. 检查传统兼容图标 (mipmap-*dpi)")
    densities = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]
    
    for density in densities:
        folder = os.path.join(RES_DIR, f"mipmap-{density}")
        if os.path.exists(folder):
            files = os.listdir(folder)
            webp_files = [f for f in files if f.endswith('.webp')]
            png_files = [f for f in files if f == 'ic_launcher.png']
            
            if webp_files:
                print(f"  ❌ mipmap-{density}: 发现残留的 webp 文件 ({', '.join(webp_files)})")
                is_perfect = False
            elif png_files:
                print(f"  ✅ mipmap-{density}: 已正确替换为 ic_launcher.png")
            else:
                print(f"  ⚠️ mipmap-{density}: 找不到 ic_launcher.png")
                is_perfect = False
        else:
            print(f"  ⚠️ mipmap-{density}: 文件夹不存在 (旧版兼容性会变差)")
    print("-" * 55)

    # 5. 检查 AndroidManifest.xml
    print("📜 5. 检查 AndroidManifest.xml")
    if os.path.exists(MANIFEST_PATH):
        with open(MANIFEST_PATH, 'r', encoding='utf-8') as f:
            content = f.read()
        if 'android:icon="@mipmap/ic_launcher"' in content:
            print("  ✅ android:icon 指向正确 (@mipmap/ic_launcher)")
        else:
            print("  ⚠️ android:icon 可能配置不正确，请检查。")
    else:
        print("  ❌ 找不到 AndroidManifest.xml")
        is_perfect = False

    print("\n" + "="*55)
    if is_perfect:
        print("🏆 完美！所有配置均已通过检查，可以直接 Clean Project 并 Run 运行！")
    else:
        print("⚠️ 检查发现一些问题，请根据上述提示（标有 ❌ 的项）进行修改。")

if __name__ == "__main__":
    check_fixed_icons()