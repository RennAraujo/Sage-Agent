package io.github.rennaraujo.sage.web;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resolução de DNS injetável: o teste troca por um mapa fixo, a produção usa o resolvedor do sistema. */
@FunctionalInterface
interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
