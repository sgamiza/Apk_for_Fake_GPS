// IMockGpsService.aidl
package com.mockgps.shizuku;

interface IMockGpsService {
    /**
     * 给指定包名授予 mock_location AppOps 权限。
     * 等价于 shell 指令：cmd appops set <pkg> android:mock_location allow
     * 调用方拥有 shell UID（2000），可以无需 root 完成此操作。
     * @return 0 表示成功，非 0 表示退出码
     */
    int grantMockLocation(String packageName) = 1;

    /**
     * 撤销 mock_location AppOps 权限。
     */
    int revokeMockLocation(String packageName) = 2;

    /**
     * 查询当前 AppOps 状态文本（执行 cmd appops get <pkg> mock_location）。
     */
    String queryMockLocation(String packageName) = 3;

    /**
     * 执行任意 shell 命令（受 shell UID 权限限制），返回标准输出。
     * 用于诊断；实际功能尽量走具体方法。
     */
    String execShell(String cmd) = 4;

    /**
     * 通过 Shizuku 强制打开系统定位主开关。
     * 优先使用 `cmd location set-location-enabled true`（Android 9+），
     * 兼容老版本会回退到 `settings put secure location_mode 3`。
     * @return 0 = 成功，非 0 = 失败的退出码
     */
    int enableLocationServices() = 5;

    /**
     * 查询系统定位主开关状态（settings get secure location_mode）。
     * 返回 0/1/2/3 之一：0=关闭, 3=高精度。
     */
    String queryLocationMode() = 6;

    /**
     * dumpsys location，截取与 mock provider 相关的关键段落。
     * 用于在 UI 上诊断「test provider 是否生效 / 是否被系统覆盖」。
     */
    String dumpLocation() = 7;

    /** 销毁服务（Shizuku 内置约定，transactionCode 必须为 16777114） */
    void destroy() = 16777114;
}
