package com.dripagency.mlbbdraft.engine

import com.dripagency.mlbbdraft.data.model.Hero
import com.dripagency.mlbbdraft.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class RoleAssignerTest {
    private fun hero(id: String, role: Role, cls: String, secondary: List<Role> = emptyList()) =
        Hero(id, id, cls, emptyList(), role, secondary, null, null)

    @Test
    fun `two primary EXP heroes get split using secondary role`() {
        val a = hero("a", Role.EXP, "Fighter")
        val b = hero("b", Role.EXP, "Fighter", secondary = listOf(Role.JUNGLE))
        val r = RoleAssigner.assign(listOf(a, b))
        assertEquals(Role.EXP, r["a"])
        assertEquals(Role.JUNGLE, r["b"])
    }

    @Test
    fun `class fallback places a tank without lane data in roam`() {
        val t = hero("t", Role.EXP, "Tank") // bad primary but class says roam
        val f = hero("f", Role.EXP, "Fighter")
        val r = RoleAssigner.assign(listOf(t, f))
        assertEquals(Role.EXP, r["f"])
        assertEquals(Role.ROAM, r["t"])
    }
}
