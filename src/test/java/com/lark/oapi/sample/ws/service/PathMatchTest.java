package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathMatchTest {
    @Test
    void normalize() {
        assertEquals("c:/work/proj", PathMatch.normalize("C:\\Work\\Proj\\"));
        assertEquals("c:/", PathMatch.normalize("C:\\"));
        assertEquals("/home/a/b", PathMatch.normalize("/home//a/./b/"));
        assertEquals("/", PathMatch.normalize("/"));
    }

    @Test
    void matching() {
        assertTrue(PathMatch.matches("/home/a/repo", "/home/a/repo/"));      // 相等
        assertTrue(PathMatch.matches("/home/a", "/home/a/repo/fe"));          // 实例目录是祖先
        assertTrue(PathMatch.matches("/home/a/repo/fe/src", "/home/a/repo/fe")); // 实例目录在工作区里
        assertFalse(PathMatch.matches("/home/a/re", "/home/a/repo"));         // 按段比较
        assertFalse(PathMatch.matches("/home/b", "/home/a/repo"));
        assertTrue(PathMatch.matches("D:\\Code\\FE", "d:/code/fe/app"));     // Windows 大小写 + 斜杠
        assertFalse(PathMatch.matches("", "/x"));
    }

    @Test
    void score() {
        List<String> ws = List.of("/src/fe", "/src/be");
        assertEquals(2, PathMatch.score("/src/fe", ws));
        assertEquals(1, PathMatch.score("/src/be/x", ws));
        assertEquals(0, PathMatch.score("/other", ws));
        assertEquals(2, PathMatch.score("/src", ws));    // 公共父目录同时覆盖两者
    }
}
