const packageJson = require('./package.json')

module.exports = {
  ...packageJson.build,
  appId: 'io.aimarketing.desktop.dev',
  productName: 'AiMarketing Dev',
  directories: {
    ...packageJson.build.directories,
    output: 'dist-dev'
  },
  extraMetadata: {
    name: 'dsh-desktop-dev',
    productName: 'AiMarketing Dev',
    dshDesktopChannel: 'development'
  },
  artifactName: 'aimarketing-desktop-dev-${os}-${arch}.${ext}',
  nsis: {
    ...packageJson.build.nsis,
    artifactName: 'aimarketing-desktop-dev-windows-${arch}-setup.${ext}'
  },
  publish: null
}
