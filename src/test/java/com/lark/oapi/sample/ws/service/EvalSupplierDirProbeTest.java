package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.EvalTask;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseResultRepository;
import com.lark.oapi.sample.ws.repository.EvalTaskRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * supplierDir 探测回归测试（11-21-supplier-eval-loop MVP-1.8 缺陷B）。
 * <p>
 * 现网实测缺陷：任务 supplierCode 是数字（真任务 id=2 就是 2226），目录名是标识串（DCYiLv）——
 * 只按 code 匹配必然 0 命中，且失败信息把 200+ 个候选目录整份罗列刷屏。
 * <p>
 * 修复语义（本测试钉死）：
 * ① 显式 supplierDir 优先（既有语义不回归，见 EvalStaticRepoPathTest ⑥）；
 * ② supplierCode 匹配目录名（大小写不敏感，既有语义）；
 * ③ 匹配不上 → supplierClassHint 兜底：先按类名原样，再去掉 Business/Service/Impl 等后缀
 *    得到标识串（DCYiLvBusiness → DCYiLv）再匹配，唯一命中才自动填；
 * ④ 还不行 → 失败信息必须点名「拿什么值找过、目录里实际有什么」，候选列表截断到
 *    {@value EvalLoopService#PROBE_LIST_LIMIT} 个 + …，并给出下一步（显式填 supplierDir）。
 */
class EvalSupplierDirProbeTest {

    @TempDir
    Path tmp;

    private EvalLoopService service;

    @BeforeEach
    void setUp() {
        Path f = Path.of(EvalTemplates.DEV_FILE).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(f), "模板库文件必须存在（测试在仓库根运行）: " + f);
        service = new EvalLoopService(mock(EvalTaskRepository.class), mock(EvalCaseRepository.class),
                mock(EvalCaseResultRepository.class), mock(BotInstanceRepository.class),
                tmp.resolve("eval-logs").toString(), f.toString(), EvalTemplates.resolveScriptDir(""));
    }

    private static EvalTask task(String supplierCode) {
        EvalTask t = new EvalTask();
        t.setId(1L);
        t.setName("probe-test");
        t.setSupplierCode(supplierCode);
        return t;
    }

    private static Map<String, String> vars(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String fwd(Path p) {
        return p.toString().replace('\\', '/');
    }

    // ===== 真值断言（用户验收原文）：supplierCode=2226 + supplierClassHint=DCYiLvBusiness → …/Supplier/DCYiLv =====

    @Test
    void realRepoNumericCodeWithClassHintResolvesToDCYiLv() {
        Path realRepo = Path.of("D:/dida/SupplierV2");
        Assumptions.assumeTrue(Files.isDirectory(realRepo.resolve("src/Business/Supplier/DCYiLv")),
                "本机没有真实供应商仓库（只读真值断言跳过）: " + realRepo);

        // 现网真任务 id=2 的同款上下文：supplierCode=2226（数字）、hint=DCYiLvBusiness
        EvalLoopService.PathResolution r = service.resolveSupplierDir(task("2226"),
                vars("repoPath", "D:/dida/SupplierV2", "supplierClassHint", "DCYiLvBusiness"));
        assertNull(r.problem(), "hint 兜底必须命中，不许再失败: " + r.problem());
        assertEquals("D:/dida/SupplierV2/src/Business/Supplier/DCYiLv", r.path());

        // 只给 code（没有 hint）→ 仍然诚实失败，且信息点名拿 2226 找过 + 截断候选 + 下一步
        EvalLoopService.PathResolution miss = service.resolveSupplierDir(task("2226"),
                vars("repoPath", "D:/dida/SupplierV2"));
        assertNull(miss.path());
        assertTrue(miss.problem().contains("supplierCode=2226"), "点名拿什么值找过: " + miss.problem());
        assertTrue(miss.problem().contains("…（共 "), "真仓 200+ 目录必须截断，不整份罗列: " + miss.problem());
        assertTrue(miss.problem().contains("显式填 supplierDir"), "给可操作下一步: " + miss.problem());
        assertTrue(miss.problem().length() < 600, "失败信息不许刷屏（旧版整份罗列 200+ 目录）: " + miss.problem().length());
    }

    // ===== fixture 断言：兜底顺序（code → hint 原样 → hint 去后缀）与唯一命中纪律 =====

    @Test
    void probeFallsBackFromNumericCodeToClassHintOnFixtureRepo() throws Exception {
        Path repo = tmp.resolve("repo");
        for (String n : List.of("DCYiLv", "Dorak", "Yeste", "ZyxTravel")) {
            Files.createDirectories(repo.resolve("src/Business/Supplier/" + n));
        }
        String repoFwd = fwd(repo);
        String dcyilv = fwd(repo.resolve("src/Business/Supplier/DCYiLv"));

        // code 匹配不上 → hint 去后缀命中（DCYiLvBusiness → DCYiLv）
        assertEquals(dcyilv, service.resolveSupplierDir(task("2226"),
                vars("repoPath", repoFwd, "supplierClassHint", "DCYiLvBusiness")).path());
        // hint 原样就是目录名 → 直接命中（不必去后缀）
        assertEquals(dcyilv, service.resolveSupplierDir(task("2226"),
                vars("repoPath", repoFwd, "supplierClassHint", "DCYiLv")).path());
        // 多层后缀迭代剥离：DorakSupplierService → DorakSupplier → Dorak
        assertEquals(fwd(repo.resolve("src/Business/Supplier/Dorak")),
                service.resolveSupplierDir(task("9999"),
                        vars("repoPath", repoFwd, "supplierClassHint", "DorakSupplierService")).path());
        // code 直接命中时轮不到 hint（② 的优先级不被兜底抢占）
        assertEquals(dcyilv, service.resolveSupplierDir(task("dcyilv"),
                vars("repoPath", repoFwd, "supplierClassHint", "DorakSupplierService")).path());
        // code、hint 都匹配不上 → 诚实失败：点名两个找过的值 + 下一步
        EvalLoopService.PathResolution miss = service.resolveSupplierDir(task("2226"),
                vars("repoPath", repoFwd, "supplierClassHint", "NoSuchSupplierBusiness"));
        assertNull(miss.path());
        assertTrue(miss.problem().contains("supplierCode=2226"), miss.problem());
        assertTrue(miss.problem().contains("NoSuchSupplierBusiness"), "hint 找过什么也要点名: " + miss.problem());
        assertTrue(miss.problem().contains("DCYiLv") && miss.problem().contains("Dorak"),
                "目录里实际有什么（≤8 个全列）: " + miss.problem());
        assertTrue(miss.problem().contains("显式填 supplierDir"), miss.problem());
    }

    @Test
    void failureMessageTruncatesCandidateWallToEightPlusEllipsis() throws Exception {
        Path repo = tmp.resolve("wall-repo");
        for (int i = 1; i <= 12; i++) {
            Files.createDirectories(repo.resolve("src/Business/Supplier/Sup" + String.format("%02d", i)));
        }
        EvalLoopService.PathResolution r = service.resolveSupplierDir(task("2226"),
                vars("repoPath", fwd(repo)));
        assertNull(r.path());
        assertTrue(r.problem().contains("…（共 12 个）"), "截断到 8 个 + …（共 N 个）: " + r.problem());
        assertTrue(r.problem().contains("Sup01") && r.problem().contains("Sup08"), "前 8 个保留: " + r.problem());
        assertFalse(r.problem().contains("Sup09"), "第 9 个起不再罗列: " + r.problem());
    }

    @Test
    void stripSupplierSuffixesIsDeterministicAndNeverStripsToEmpty() {
        assertEquals("DCYiLv", EvalLoopService.stripSupplierSuffixes("DCYiLvBusiness"));
        assertEquals("Dorak", EvalLoopService.stripSupplierSuffixes("DorakSupplierService"));
        assertEquals("X", EvalLoopService.stripSupplierSuffixes("XImpl"));
        assertEquals("Plain", EvalLoopService.stripSupplierSuffixes("Plain"), "无后缀可剥 → 原样");
        assertEquals("Business", EvalLoopService.stripSupplierSuffixes("Business"), "绝不剥成空串");
        assertEquals("", EvalLoopService.stripSupplierSuffixes(""));
        assertEquals("", EvalLoopService.stripSupplierSuffixes(null));
    }
}
