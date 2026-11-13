package com.nemesis.apk

import java.net.URI

/** Parse direct SSH endpoints such as host, user@host, host:port or ssh://user@host:port. */
data class SshEndpoint(val host:String,val port:Int,val user:String) {
    companion object {
        fun parse(input:String, portInput:String, userInput:String):SshEndpoint {
            val raw=input.trim(); require(raw.isNotEmpty() && raw.length<=512) { "Hôte ou URL SSH requis" }
            require(raw.none { it.code<32 || it.isWhitespace() }) { "L’adresse SSH ne peut contenir d’espaces ou de contrôles." }
            var host=raw
            val portText=portInput.trim()
            var port=if(portText.isEmpty()) 22 else portText.toIntOrNull() ?: throw IllegalArgumentException("Port SSH invalide")
            var user=userInput.trim()
            if(raw.startsWith("ssh://",true)) {
                val u=try { URI(raw) } catch(_:Exception) { throw IllegalArgumentException("URL SSH invalide") }
                require(u.scheme.equals("ssh",true) && u.host!=null && u.userInfo?.contains(':')!=true && u.query==null && u.fragment==null && (u.path.isNullOrEmpty() || u.path=="/")) { "Format attendu : ssh://[utilisateur@]hote[:port]" }
                host=u.host
                if(u.port!=-1) port=u.port
                if(!u.userInfo.isNullOrBlank()) user=u.userInfo
            } else if(raw.contains('@') && !raw.startsWith("[")) {
                val idx=raw.lastIndexOf('@'); require(idx>0 && idx<raw.lastIndex) { "Format utilisateur@hote invalide" }
                user=raw.substring(0,idx)
                host=raw.substring(idx+1)
            }
            if(!raw.startsWith("ssh://") && host.startsWith("[") && host.contains("]:")) {
                val end=host.indexOf(']'); val candidate=host.substring(end+2)
                if(candidate.isNotEmpty()) port=candidate.toIntOrNull() ?: throw IllegalArgumentException("Port SSH invalide")
                host=host.substring(1,end)
            } else if(!raw.startsWith("ssh://") && !host.startsWith("[") && host.count { it==':' }==1) {
                val idx=host.lastIndexOf(':'); val suffix=host.substring(idx+1)
                if(suffix.all { it.isDigit() }) { port=suffix.toIntOrNull() ?: 0; host=host.substring(0,idx) }
            }
            require(host.isNotBlank() && host.length<=253 && host.matches(Regex("[A-Za-z0-9._:-]+"))) { "Nom d’hôte SSH invalide." }
            require(port in 1..65535) { "Port SSH invalide (1–65535)." }
            require(user.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Nom d’utilisateur SSH invalide." }
            return SshEndpoint(host,port,user)
        }
    }
}
