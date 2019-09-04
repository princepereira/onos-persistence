java_library(
    name = 'core',
    visibility = ['PUBLIC'],
    deps = CORE,
)

java_library(
    name = 'apps',
    visibility = ['PUBLIC'],
    deps = APPS + APP_JARS,
)

java_library(
    name = 'onos',
    visibility = ['PUBLIC'],
    deps = [ ':core', ':apps' ]
)

INSTALL = [
    '//utils/misc:onlab-misc-install',
    '//utils/osgi:onlab-osgi-install',
    '//utils/rest:onlab-rest-install',

    '//core/api:onos-api-install',
    '//incubator/api:onos-incubator-api-install',

    '//core/net:onos-core-net-install',
    '//core/common:onos-core-common-install',
    '//core/store/dist:onos-core-dist-install',
    '//core/store/primitives:onos-core-primitives-install',
    '//core/store/persistence:onos-core-persistence-install',
    '//core/store/serializers:onos-core-serializers-install',

    '//incubator/net:onos-incubator-net-install',
    '//incubator/store:onos-incubator-store-install',
    '//incubator/rpc:onos-incubator-rpc-install',

    '//core/security:onos-security-install',

    '//web/api:onos-rest-install',
    '//web/gui:onos-gui-install',
    '//cli:onos-cli-install',
]
java_library(
    name = 'install',
    visibility = ['PUBLIC'],
    deps = INSTALL
)

tar_file(
    name = 'onos-test',
    root = 'onos-test-%s' % ONOS_VERSION,
    srcs = glob(['tools/test/**/*']) + [
               'tools/dev/bash_profile',
               'tools/dev/bin/onos-create-app',
               'tools/build/envDefaults'
           ],
    other_tars = [ '//tools/package:onos-package-runtime' ],
)

tar_file(
    name = 'onos-admin',
    root = 'onos-admin-%s' % ONOS_VERSION,
    srcs = [
        'tools/dev/bin/onos-create-app',
        'tools/test/bin/onos',
    ],
    other_tars = [ '//tools/package:onos-package-runtime' ],
    flat = True,
)

only_lib_dep_pom(
    name = 'top-level-pom',
    src = 'pom.xml',
    out = 'onos.pom',
)




