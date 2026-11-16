/*
    YARA rules for FyreAC -> PaperFix (FyreRAT)
    -------------------------------------------
    Two-stage Minecraft server backdoor:
      Stage 1: hidden downloader inside a trojanized FyreAC/GrimAC plugin
      Stage 2: single-class WebSocket RAT disguised as a PaperMC plugin

    Samples analysed:
      Stage 1 JAR:
        SHA-256: ad6e0d958eb1a8967f1d06536aa4ed98e1970721bd2b5b2dc7cb2e87284da5d6
        Size:    14877457 bytes
      Stage 1 stager:
        ac/grim/grimac/shaded/fastutil/ints/ConsumerIntBig.class
        SHA-256: 8392f1d3fa9f7c981212fe678df96c95c24adaf294f809f12678abd0c7ad760d
        Size:    7308 bytes
      Stage 1 modified loader:
        ac/grim/grimac/platform/bukkit/GrimACBukkitLoaderPlugin.class
        SHA-256: 79ddf28ae7a583314c6c3df9fb9f6cd5038a8d8d722c6a14dd69f25e0181656f
        Size:    18040 bytes
      Stage 2 JAR:
        SHA-256: fb52ed5592921a3f6dae2ad32e9e19874803fe554c2e208d4c5f164f851d511c
        Size:    11885290 bytes
      Stage 2 RAT:
        org/papermc/paperfix/paperfix.class
        SHA-256: 701da967affae189f6e3a1a853ee36fad6a13ed8cd4ad71a231ba0aa7548a8e1
        Size:    15778 bytes

    Notes:
      * The class rules match extracted .class files. Class strings are
        deflated inside the JAR, so use the JAR rules for raw archives.
      * The C2 indicator rule also matches this README/report text. Exclude
        analysis documents from production scans.
      * The domain is a Cloudflare Workers subdomain and may rotate; pair it
        with the host behavior described in Other/FyreRAT/README.md.

    Author: JohnAtheism
    Date:   2026-11-16
    TLP:    WHITE
*/

import "hash"


rule FyreRAT_Sample_KnownHash
{
    meta:
        family        = "FyreRAT"
        stage         = "all"
        confidence    = "high"
        description   = "Exact known samples of the FyreAC -> PaperFix chain"
        reference     = "https://github.com/JohnAtheism/yara/tree/main/Other/FyreRAT"
        sample_sha256 = "ad6e0d958eb1a8967f1d06536aa4ed98e1970721bd2b5b2dc7cb2e87284da5d6, fb52ed5592921a3f6dae2ad32e9e19874803fe554c2e208d4c5f164f851d511c"
    condition:
        ( filesize == 14877457 and hash.sha256(0, filesize) == "ad6e0d958eb1a8967f1d06536aa4ed98e1970721bd2b5b2dc7cb2e87284da5d6" ) or
        ( filesize == 11885290 and hash.sha256(0, filesize) == "fb52ed5592921a3f6dae2ad32e9e19874803fe554c2e208d4c5f164f851d511c" ) or
        ( filesize == 7308     and hash.sha256(0, filesize) == "8392f1d3fa9f7c981212fe678df96c95c24adaf294f809f12678abd0c7ad760d" ) or
        ( filesize == 18040    and hash.sha256(0, filesize) == "79ddf28ae7a583314c6c3df9fb9f6cd5038a8d8d722c6a14dd69f25e0181656f" ) or
        ( filesize == 15778    and hash.sha256(0, filesize) == "701da967affae189f6e3a1a853ee36fad6a13ed8cd4ad71a231ba0aa7548a8e1" )
}


rule FyreRAT_Stage1_JAR
{
    meta:
        family      = "FyreRAT"
        stage       = "stage1-jar"
        confidence  = "high"
        description = "Trojanized FyreAC JAR containing the ConsumerIntBig stager"
        reference   = "ConsumerIntBig.class in ac/grim/grimac/shaded/fastutil/ints/"

    strings:
        $stager = "ac/grim/grimac/shaded/fastutil/ints/ConsumerIntBig.class" ascii
        $loader = "GrimACBukkitLoaderPlugin.class" ascii

    condition:
        uint16(0) == 0x4B50 and
        filesize < 50MB and
        $stager and $loader
}


rule FyreRAT_Stage1_Stager_Class
{
    meta:
        family      = "FyreRAT"
        stage       = "stage1-class"
        confidence  = "high"
        description = "Extracted ConsumerIntBig downloader class"
        reference   = "plugins/paperfix-3.2.6.jar drop path + /api/asdfg2asd manifest"

    strings:
        $url    = "http://crimson-tree-cf44.blaher331.workers.dev/api/asdfg2asd" ascii
        $method = "comparatorsIndirect" ascii
        $path   = "plugins/paperfix-3.2.6.jar" ascii
        $also   = "namePattern" ascii
        $sha    = "sha256" ascii

    condition:
        uint32be(0) == 0xCAFEBABE and
        $url and 2 of ($method, $path, $also, $sha)
}


rule FyreRAT_Stage1_Distribution
{
    meta:
        family      = "FyreRAT"
        stage       = "stage1-distribution"
        confidence  = "high"
        description = "Stage-1 manifest payload host and replacement-plugin downloads"
        reference   = "Other/FyreRAT/manifest.txt"
        scope_note  = "Also matches the captured manifest and reports that quote these URLs."

    strings:
        $gitlab  = "gitlab.com/blaher331/nigea" ascii wide nocase
        $fyreac  = "github.com/fyreac/fyreac/releases/download/67/" ascii wide nocase
        $luck    = "LuckPerms-Bukkit-5.5.53.jar" ascii wide
        $voice   = "voicechat-bukkit-2.6.23.jar" ascii wide
        $tab     = "TAB.v6.1.3.jar" ascii wide

    condition:
        $gitlab or ( $fyreac and 1 of ($luck, $voice, $tab) )
}


rule FyreRAT_Stage1_Loader_Or_Stager
{
    meta:
        family      = "FyreRAT"
        stage       = "stage1-class"
        confidence  = "medium"
        description = "Class referencing ConsumerIntBig.comparatorsIndirect; matches the stager and the modified loader"
        reference   = "GrimACBukkitLoaderPlugin.onEnable() call site"

    strings:
        $cls    = "ac/grim/grimac/shaded/fastutil/ints/ConsumerIntBig" ascii
        $method = "comparatorsIndirect" ascii

    condition:
        uint32be(0) == 0xCAFEBABE and all of them
}


rule FyreRAT_Stage2_JAR
{
    meta:
        family      = "FyreRAT"
        stage       = "stage2-jar"
        confidence  = "high"
        description = "PaperFix RAT bundle with relocated JDA/Jackson/OkHttp libraries"
        reference   = "plugin.yml: paperfix 3.2.6, main org.papermc.paperfix.paperfix, load STARTUP"

    strings:
        $rat   = "org/papermc/paperfix/paperfix.class" ascii
        $libs  = "org/papermc/paperfix/libs/" ascii
        $jda   = "org/papermc/paperfix/libs/net/dv8tion/jda/" ascii

    condition:
        uint16(0) == 0x4B50 and
        filesize < 50MB and
        $rat and 1 of ($libs, $jda)
}


rule FyreRAT_Stage2_RAT_Class
{
    meta:
        family      = "FyreRAT"
        stage       = "stage2-class"
        confidence  = "high"
        description = "Extracted PaperFix WebSocket RAT class"
        reference   = "org/papermc/paperfix/paperfix.class"

    strings:
        $domain = "crimson-tree-cf44.blaher331.workers.dev" ascii
        $reg    = "/api/sdf8gusdf3/register" ascii
        $ws     = "/api/aguysdfsd89asdfa" ascii
        $cache  = ".cache" ascii
        $state  = ".srvinfo" ascii
        $aes    = "AES/GCM/NoPadding" ascii
        $close  = "Plugin disabled" ascii

    condition:
        uint32be(0) == 0xCAFEBABE and
        $domain and 2 of ($reg, $ws, $cache, $state, $aes, $close)
}


rule FyreRAT_C2_Indicators
{
    meta:
        family      = "FyreRAT"
        stage       = "network"
        confidence  = "medium"
        description = "FyreRAT C2 host and protocol paths in memory dumps or unpacked files"
        scope_note  = "Also matches analysis documents; exclude reports and README files from production scans."

    strings:
        $domain = "crimson-tree-cf44.blaher331.workers.dev" ascii wide nocase
        $p1     = "/api/asdfg2asd" ascii wide
        $p2     = "/api/sdf8gusdf3/register" ascii wide
        $p3     = "/api/aguysdfsd89asdfa" ascii wide
        $p4     = "/api/sasd2" ascii wide
        $state  = ".cache/.srvinfo" ascii wide
        $method = "comparatorsIndirect" ascii wide

    condition:
        ( $domain and 1 of ($p1, $p2, $p3, $p4) ) or
        ( 2 of ($p1, $p2, $p3, $p4) ) or
        ( $domain and $state ) or
        ( $domain and $method )
}


rule FyreRAT_Web_IPLogger
{
    meta:
        family      = "FyreRAT"
        stage       = "web"
        confidence  = "high"
        description = "fyreac.space IP-logging script that posts visitor IPs to /api/sasd2"
        reference   = "Other/FyreRAT/script.js"
        scope_note  = "Matches the captured script and pages that quote its source."

    strings:
        $ipify   = "api.ipify.org" ascii wide
        $exfil   = "/api/sasd2" ascii wide
        $fake1   = "fake1" ascii wide
        $fake2   = "fake2" ascii wide
        $opened  = "Opened" ascii wide
        $downed  = "Downloaded" ascii wide

    condition:
        $exfil and 2 of ($ipify, $fake1, $fake2, $opened, $downed)
}
