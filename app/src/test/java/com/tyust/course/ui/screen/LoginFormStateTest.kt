package com.tyust.course.ui.screen

import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test

class LoginFormStateTest {
    @Test fun rotationKeepsFormButSchoolOrAdapterChangeClearsSensitiveFields() {
        val state=LoginFormState(); state.selectContext("school:1","")
        state.password="synthetic-password"; state.cookie="SYNTHETIC=cookie"
        state.selectContext("school:1","")
        assertEquals("synthetic-password",state.password); assertEquals("SYNTHETIC=cookie",state.cookie)
        state.selectContext("school:2","")
        assertEquals("",state.password); assertEquals("",state.cookie)
        state.password="synthetic-password"
        val store=ViewModelStore(); store.put("login",state); store.clear()
        assertEquals("",state.password)
    }
}
