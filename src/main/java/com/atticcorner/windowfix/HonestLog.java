package com.atticcorner.windowfix;

/**
 * 诚实记账（案卷 §13 教训：注入 ≠ 执行）。
 * 每刀应用/跳过都必须 stderr 一行 "[windowfix] <site> ..."，运行期（游戏进程控制台）可见，
 * 作验收第 4 条的运行证据。
 */
public final class HonestLog {

    private HonestLog() {
    }

    public static void log(String site, String message) {
        System.err.println("[windowfix] " + site + " " + message);
    }
}
