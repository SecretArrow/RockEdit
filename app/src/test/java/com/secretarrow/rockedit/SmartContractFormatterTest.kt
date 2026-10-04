package com.secretarrow.rockedit

import com.secretarrow.rockedit.core.FormatOptions
import com.secretarrow.rockedit.core.FormatRequest
import com.secretarrow.rockedit.core.FormatResult
import com.secretarrow.rockedit.core.FormatterRegistry
import com.secretarrow.rockedit.core.SyntaxRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage of the smart-contract language support:
 * every shipped contract language formats a realistic snippet, routes to
 * the right engine, is idempotent, and its file extension resolves through
 * [SyntaxRegistry] so the Format action picks the engine automatically.
 */
class SmartContractFormatterTest {

    private val registry = FormatterRegistry.default()

    private fun run(text: String, language: String) =
        registry.format(FormatRequest(text, language, FormatOptions(insertFinalNewline = false)))

    private fun ok(result: FormatResult): String {
        assertTrue("expected Success but was $result", result is FormatResult.Success)
        return (result as FormatResult.Success).formattedText
    }

    private fun expectBrace(language: String, src: String, expected: String) {
        assertEquals("brace", registry.formatterFor(language)?.id)
        val out = ok(run(src, language))
        assertEquals(expected, out)
        assertEquals("not idempotent for $language", out, ok(run(out, language)))
    }

    // ------------------------------------------------------------- Solidity

    @Test
    fun solidityContract() {
        expectBrace(
            "solidity",
            "pragma solidity ^0.8.20;\n\ncontract Token is IERC20 {\n" +
                "mapping(address => uint256) private _balances;\n\n" +
                "function transfer(address to, uint256 amount) public returns (bool) {\n" +
                "require(to != address(0), \"zero\");\n_balances[msg.sender] -= amount;\n" +
                "return true;\n}\n}",
            "pragma solidity ^0.8.20;\n\ncontract Token is IERC20 {\n" +
                "    mapping(address => uint256) private _balances;\n\n" +
                "    function transfer(address to, uint256 amount) public returns (bool) {\n" +
                "        require(to != address(0), \"zero\");\n" +
                "        _balances[msg.sender] -= amount;\n" +
                "        return true;\n" +
                "    }\n}"
        )
    }

    @Test
    fun solidityAliasSolRoutesTheSame() {
        assertEquals("brace", registry.formatterFor("sol")?.id)
        assertEquals(
            ok(run("contract A {\nuint x;\n}", "solidity")),
            ok(run("contract A {\nuint x;\n}", "sol"))
        )
    }

    // ---------------------------------------------------------------- Vyper

    @Test
    fun vyperContract() {
        assertEquals("indent", registry.formatterFor("vyper")?.id)
        val src = "@external\ndef balance_of(owner: address) -> uint256:\n  return self.balances[owner]"
        val out = ok(run(src, "vyper"))
        assertEquals(
            "@external\ndef balance_of(owner: address) -> uint256:\n    return self.balances[owner]",
            out
        )
        assertEquals(out, ok(run(out, "vyper")))
    }

    // ----------------------------------------------------------------- Move

    @Test
    fun moveModule() {
        expectBrace(
            "move",
            "module examples::coin {\nstruct Coin has key {\nvalue: u64\n}\n\n" +
                "public fun mint(value: u64): Coin {\nCoin { value }\n}\n}",
            "module examples::coin {\n    struct Coin has key {\n        value: u64\n    }\n\n" +
                "    public fun mint(value: u64): Coin {\n        Coin { value }\n    }\n}"
        )
    }

    // ---------------------------------------------------------------- Cairo

    @Test
    fun cairoContract() {
        expectBrace(
            "cairo",
            "#[starknet::contract]\nmod Token {\n#[storage]\nstruct Storage {\n" +
                "total_supply: u256,\n}\n}",
            "#[starknet::contract]\nmod Token {\n    #[storage]\n    struct Storage {\n" +
                "        total_supply: u256,\n    }\n}"
        )
    }

    // -------------------------------------------------------------- Clarity

    @Test
    fun clarityContract() {
        assertEquals("lisp", registry.formatterFor("clarity")?.id)
        val src = "(define-data-var counter int 0)\n(define-public (inc)\n(begin\n" +
            "(var-set counter (+ 1 (var-get counter)))\n(ok (var-get counter))\n)\n)"
        val out = ok(run(src, "clarity"))
        // (inc) closes on the opener line, so the body sits one level in.
        assertTrue("begin must be indented one level", out.contains("\n    (begin"))
        assertTrue("body must be two levels deep", out.contains("\n        (var-set counter"))
        assertEquals(out, ok(run(out, "clarity")))
    }

    // -------------------------------------------------------------- Cadence

    @Test
    fun cadenceContract() {
        expectBrace(
            "cadence",
            "access(all) contract Counter {\naccess(all) var count: Int\n\n" +
                "access(all) fun add() {\nself.count = self.count + 1\n}\n}",
            "access(all) contract Counter {\n    access(all) var count: Int\n\n" +
                "    access(all) fun add() {\n        self.count = self.count + 1\n    }\n}"
        )
    }

    // -------------------------------------------------------------- Motoko

    @Test
    fun motokoActor() {
        expectBrace(
            "motoko",
            "actor Counter {\nvar count : Nat = 0;\n\npublic func add() : async Nat {\n" +
                "count += 1;\nreturn count;\n}\n}",
            "actor Counter {\n    var count : Nat = 0;\n\n" +
                "    public func add() : async Nat {\n        count += 1;\n        return count;\n    }\n}"
        )
    }

    // --------------------------------------------------------------- Aiken

    @Test
    fun aikenValidator() {
        expectBrace(
            "aiken",
            "validator spend {\nmint(datum: Option<Data>, rdmr: Data) {\nTrue\n}\n}",
            "validator spend {\n    mint(datum: Option<Data>, rdmr: Data) {\n        True\n    }\n}"
        )
    }

    // ------------------------------------------------------------------ Leo

    @Test
    fun leoProgram() {
        expectBrace(
            "leo",
            "program token.aleo {\ntransfer(receiver: address, amount: u64) {\nassert(amount > 0u64);\n}\n}",
            "program token.aleo {\n    transfer(receiver: address, amount: u64) {\n" +
                "        assert(amount > 0u64);\n    }\n}"
        )
    }

    // ------------------------------------------------------------------- Fe

    @Test
    fun feContract() {
        expectBrace(
            "fe",
            "contract Wallet {\nowner: address;\npub fn deposit(mut self, amount: u256) {\nassert(amount > 0);\n}\n}",
            "contract Wallet {\n    owner: address;\n" +
                "    pub fn deposit(mut self, amount: u256) {\n        assert(amount > 0);\n    }\n}"
        )
    }

    // ------------------------------------------------------------ Michelson

    @Test
    fun michelsonContract() {
        assertEquals("lisp", registry.formatterFor("michelson")?.id)
        val out = ok(run("(parameter unit)\n(storage unit)\n(code\n(PUSH unit (UNIT))\n)", "michelson"))
        assertEquals(
            "(parameter unit)\n(storage unit)\n(code\n    (PUSH unit (UNIT))\n)",
            out
        )
        assertEquals(out, ok(run(out, "michelson")))
    }

    // ---------------------------------------- Rust contract dialects (alias)

    @Test
    fun rustContractDialectsRouteToBrace() {
        for (alias in listOf("cosmwasm", "ink", "soroban")) {
            assertEquals("alias $alias must route to brace", "brace", registry.formatterFor(alias)?.id)
            val out = ok(run("#[cfg_attr(not(feature = \"library\"), entry_point)]\npub fn f() {\n}", alias))
            assertTrue(out.contains("\n}"))
        }
    }

    // ------------------------- File extension detection via SyntaxRegistry

    @Test
    fun contractFileExtensionsResolveAndRoute() {
        val cases = mapOf(
            "Router.sol" to "brace",
            "vault.vy" to "indent",
            "coin.move" to "brace",
            "token.cairo" to "brace",
            "dao.clar" to "lisp",
            "escrow.tz" to "lisp"
        )
        for ((name, engine) in cases) {
            val language = SyntaxRegistry.languageForFileName(name)
            assertTrue("extension must resolve for $name", language != null)
            assertEquals("wrong engine for $name", engine, registry.formatterFor(language!!.id)?.id)
        }
    }

    @Test
    fun everyRegistryLanguageHasAFormatter() {
        val registry = FormatterRegistry.default()
        for (id in listOf(
            "kotlin", "java", "javascript", "typescript", "go", "rust", "python",
            "ruby", "lua", "elixir", "julia", "latex", "clojure", "scheme", "lisp",
            "yaml", "json", "xml", "css", "solidity", "vyper", "move", "cairo",
            "clarity", "michelson"
        )) {
            assertTrue("no formatter for $id", registry.formatterFor(id) != null)
        }
    }
}
