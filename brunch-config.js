module.exports = {
    files: {
        stylesheets: {joinTo: 'css/haus.css'}
    },

    paths: {
        watched: [
            'assets/css',
        ],
        public: 'resources/public'
    },

    conventions: {
        ignored: [
            /(^|\/)_/,
            /(^|\/)bulma/
        ],
        assets: []
    },

    plugins: {
        sass: {
            mode: 'native'
        },
        postcss: {
            processors: [require('autoprefixer')]
        }
    }
}
