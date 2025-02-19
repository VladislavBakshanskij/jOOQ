/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: https://www.jooq.org/legal/licensing
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.impl;

import static org.jooq.VisitListener.onVisitStart;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.Tools.configuration;

import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Meta;
import org.jooq.MetaProvider;
import org.jooq.Name;
import org.jooq.Name.Quoted;
import org.jooq.Queries;
import org.jooq.Query;
import org.jooq.ResultQuery;
import org.jooq.Source;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.conf.SettingsTools;
import org.jooq.exception.DataAccessException;
import org.jooq.tools.JooqLogger;
import org.jooq.tools.jdbc.JDBCUtils;

/**
 * {@link MetaProvider} implementation which can {@link MetaProvider#provide()
 * provide} a {@link Meta} implementation based on a set of DDL scripts as the
 * input.
 *
 * @author Knut Wannheden
 */
final class TranslatingMetaProvider implements MetaProvider {

    private static final JooqLogger log    = JooqLogger.getLogger(TranslatingMetaProvider.class);
    private static final Pattern    P_NAME = Pattern.compile("(?s:.*?\"([^\"]*)\".*)");

    private final Configuration     configuration;
    private final Source[]          scripts;

    public TranslatingMetaProvider(Configuration configuration, Source... scripts) {
        this.configuration = configuration(configuration);
        this.scripts = scripts;
    }

    @Override
    public Meta provide() {
        try (DDLDatabaseInitializer initializer = new DDLDatabaseInitializer()) {
            for (Source script : scripts)
                initializer.loadScript(script);

            return new Snapshot(new DefaultMetaProvider(
                configuration.derive().set(initializer.connection).set(configuration.settings().getInterpreterDialect())
            ).provide());
        }
    }

    final class DDLDatabaseInitializer implements AutoCloseable {
        private Connection connection;
        private DSLContext ctx;

        private DDLDatabaseInitializer() {
            try {
                Settings settings = configuration.settings();

                connection = configuration.interpreterConnectionProvider().acquire();
                ctx = DSL.using(connection, settings.getInterpreterDialect(), settings);

                // [#7771] [#8011] Ignore all parsed storage clauses when executing the statements
                ctx.data("org.jooq.ddl.ignore-storage-clauses", true);

                // [#8910] Parse things a bit differently for use with the DDLDatabase
                ctx.data("org.jooq.ddl.parse-for-ddldatabase", true);

                final RenderNameCase nameCase = settings.getRenderNameCase();
                final Locale locale = SettingsTools.interpreterLocale(ctx.settings());
                if (nameCase != null && nameCase != RenderNameCase.AS_IS) {
                    ctx.configuration().set(onVisitStart(c -> {
                        if (c.queryPart() instanceof Name n) {
                            Name[] parts = n.parts();
                            boolean changed = false;

                            for (int i = 0; i < parts.length; i++) {
                                Name replacement = parts[i];
                                switch (nameCase) {
                                    case LOWER_IF_UNQUOTED:
                                        if (parts[i].quoted() == Quoted.QUOTED) break;
                                    case LOWER:
                                        replacement = DSL.quotedName(parts[i].first().toLowerCase(locale));
                                        break;

                                    case UPPER_IF_UNQUOTED:
                                        if (parts[i].quoted() == Quoted.QUOTED) break;
                                    case UPPER:
                                        replacement = DSL.quotedName(parts[i].first().toUpperCase(locale));
                                        break;

                                    default:
                                        break;
                                }
                                if (!replacement.equals(parts[i])) {
                                    parts[i] = replacement;
                                    changed = true;
                                }
                            }

                            if (changed)
                                c.queryPart(DSL.name(parts));
                        }
                    }));
                }
            }
            catch (Exception e) {
                throw new DataAccessException("Error while exporting schema", e);
            }
        }

        /**
         * Parses and executes the script represented by {@code reader} against the
         * H2 database. If the script references a schema which doesn't exist, it
         * will be automatically created first.
         * <p>
         * Any parser errors will be thrown. It is however possible to delimit
         * sections which cannot be parsed using special comments.
         *
         * @see Settings#getParseIgnoreCommentStart()
         * @see Settings#getParseIgnoreCommentStop()
         */
        private final void loadScript(Source source) {
            Reader r = null;

            try {
                Scanner s = new Scanner(r = source.reader()).useDelimiter("\\A");
                Queries queries = ctx.parser().parse(s.hasNext() ? s.next() : "");

                for (Query query : queries) {

                    repeat:
                    for (;;) {
                        try {
                            log.info(query);

                            if (query instanceof ResultQuery)
                                log.info("\n" + ((ResultQuery<?>) query).fetch());
                            else
                                log.info("Update count: " + query.execute());

                            break repeat;
                        }
                        catch (DataAccessException e) {

                            // [#7039] Auto create missing schemas. We're using the
                            if ("90079" /* ErrorCode.SCHEMA_NOT_FOUND_1 */.equals(e.sqlState())) {
                                SQLException cause = e.getCause(SQLException.class);

                                if (cause != null) {
                                    Matcher m = P_NAME.matcher(cause.getMessage());

                                    if (m.find()) {
                                        Query createSchema = ctx.createSchemaIfNotExists(name(m.group(1)));
                                        createSchema.execute();
                                        log.info(createSchema);
                                        continue repeat;
                                    }
                                }
                            }

                            throw e;
                        }
                    }
                }
            }
            catch (DataAccessException e) {

                // [#9138] Make users aware of the new parse ignore comment syntax
                log.error("DDL interpretation",
                    """
                    Your SQL string could not be parsed or interpreted. This may have a variety of reasons, including:
                    - The jOOQ parser doesn't understand your SQL
                    - The jOOQ DDL interpretation logic (translating to H2) cannot simulate your SQL

                    If you think this is a bug or a feature worth requesting, please report it here: https://github.com/jOOQ/jOOQ/issues/new/choose

                    As a workaround, you can use the Settings.parseIgnoreComments syntax documented here:
                    https://www.jooq.org/doc/latest/manual/sql-building/dsl-context/custom-settings/settings-parser/
                    """
                );

                throw e;
            }
            finally {
                JDBCUtils.safeClose(r);
            }
        }

        @Override
        public void close() {
            configuration.interpreterConnectionProvider().release(connection);
        }
    }
}
