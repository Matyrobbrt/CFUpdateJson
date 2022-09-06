package com.matyrobbrt.cfupdatejson

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.JsonObject
import groovy.transform.CompileStatic
import io.github.matyrobbrt.curseforgeapi.CurseForgeAPI
import io.github.matyrobbrt.curseforgeapi.request.AsyncRequest
import io.github.matyrobbrt.curseforgeapi.request.Requests
import io.github.matyrobbrt.curseforgeapi.request.Response
import io.github.matyrobbrt.curseforgeapi.request.query.PaginationQuery
import io.github.matyrobbrt.curseforgeapi.schemas.file.File
import io.github.matyrobbrt.curseforgeapi.util.CurseForgeException
import io.github.matyrobbrt.curseforgeapi.util.Pair
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpCode

import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

@CompileStatic
class CFUpdateJson {
    static CurseForgeAPI cfApi
    static Cache<Integer, ResponseData> cache
    static void main(String[] args) {
        final app = Javalin.create()

        cfApi = CurseForgeAPI.builder()
                .apiKey(System.getProperty('cfKey', args.length < 1 ? null : args[0]))
                .build()
        final cachingTimeout = Long.getLong('cachingTimeout', 60 * 60 * 6)
        cache = Caffeine.newBuilder()
            .maximumSize(Integer.getInteger('cacheMaxSize', 5_000))
            .expireAfterWrite(cachingTimeout, TimeUnit.SECONDS)
            .executor(Executors.newSingleThreadExecutor())
            .<Integer, ResponseData>build()

        app.get('/promos/{projectId}') {
            Integer pId
            try {
                pId = Integer.parseInt(it.pathParam('projectId'))
            } catch (Exception ignored) {
                it.status(HttpCode.BAD_REQUEST).result("{\"error\": \"Provided ID is not a valid integer.\"}")
                return
            }
            final existing = cache.getIfPresent(pId)
            if (existing !== null) {
                it.status(existing.statusCode()).result(existing.response())
                return
            }
            generatePromo(pId, it)
        }

        app.start(Integer.getInteger('appPort', 8080))
    }

    @SuppressWarnings('ChangeToOperator')
    static void generatePromo(int projectId, Context context) throws CurseForgeException {
        cfApi.makeAsyncRequest(Requests.getMod(projectId))
                .flatMap { _it ->
                    return getAllFilesForMod(projectId, cfApi).map { Pair.of(_it, it) }
                }
                .queue {
                    if (it.first().isEmpty()) {
                        final message = "{\"error\": \"Unknown project with ID: $projectId\"}"
                        cache.put(projectId, new ResponseData(404, message))
                        context.result(message).status(404)
                        return
                    }
                    final mod = it.first().get()
                    final files = it.second().isEmpty() ? List.<File>of() : it.second().get()

                    final Map<String, String> versions = [:]

                    files.stream()
                            .filter {'Forge' in it.gameVersions() }
                            .sorted(Comparator.<File, Instant>comparing {Instant.parse(it.fileDate())})
                            .forEach {
                                getMcVersions(it.gameVersions()).forEach { mc ->
                                    if (!versions.containsKey(mc)) {
                                        final version = computeVersion(it)
                                        if (version !== null) {
                                            versions.put(mc, version)
                                        }
                                    }
                                }
                            }

                    final promo = makePromo(
                            mod.links().websiteUrl(),
                            versions
                    )
                    cache.put(projectId, new ResponseData(200, promo))
                    context.result(promo).status(200)
                }
    }

    static List<String> getMcVersions(List<String> versions) {
        return versions.stream().filter {it.startsWith('1.')}.toList()
    }

    static String computeVersion(File file) {
        return Pattern.compile('-(?<version>[^-]*)\\.jar').matcher(file.fileName()).with {
            if (it.find()) {
                return it.group('version')
            }
            return null
        }
    }

    static String makePromo(String homepage, Map<String, String> versions) {
        final json = new JsonObject()
        json.addProperty('homepage', homepage)

        final promos = new JsonObject()
        versions.forEach { mc, version ->
            promos.addProperty("$mc-latest", version)
        }

        json.add('promos', promos)
        return CurseForgeAPI.DEFAULT_GSON.toJson(json)
    }

    record ResponseData(int statusCode, String response) {}

    static AsyncRequest<Response<List<File>>> getAllFilesForMod(int modId, CurseForgeAPI api) {
        AtomicInteger currentAmount = new AtomicInteger()
        return getAllFilesForMod(modId, currentAmount, api.makeAsyncRequest(Requests.getModFiles(modId)), api)
    }

    static AsyncRequest<Response<List<File>>> getAllFilesForMod(int modId, AtomicInteger amount, AsyncRequest<Response<List<File>>> request, CurseForgeAPI api) {
        return request
                .<Response<List<File>>, Exception>flatMapWithException { _it ->
                    if (_it.isEmpty()) return AsyncRequest.of(_it)
                    if (_it.get().size() < 50) return AsyncRequest.of(_it)
                    return getAllFilesForMod(modId, amount,
                            api.makeAsyncRequest(Requests.getModFiles(modId, null, PaginationQuery.of(amount.addAndGet(50)))), api)
                            .map {
                                it.isEmpty() ? _it : Response.of(_it.get() + it.get(), it.statusCode)
                            }
                }
    }
}
