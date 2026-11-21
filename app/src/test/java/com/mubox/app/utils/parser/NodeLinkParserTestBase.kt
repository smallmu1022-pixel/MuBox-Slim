package com.mubox.app.utils.parser

import com.google.gson.Gson
import org.junit.Before

abstract class NodeLinkParserTestBase {
    protected lateinit var parser: NodeLinkParser
    protected val gson = Gson()

    @Before
    fun setUp() {
        parser = NodeLinkParser(gson)
    }
}
