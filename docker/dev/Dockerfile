FROM openjdk:8

LABEL maintainer "Mathieu ANCELIN"

ENV HOME /root

WORKDIR $HOME

COPY . $HOME/

RUN apt-get update -y
RUN apt-get install -y git wget curl vim tmux openssh-server zsh ack tig tree apt-transport-https openssl build-essential
RUN git clone https://github.com/robbyrussell/oh-my-zsh.git
RUN wget --quiet https://github.com/sbt/sbt/releases/download/v1.1.0/sbt-1.1.0.zip
RUN unzip $HOME/sbt-1.1.0.zip
RUN mkdir -p $HOME/.sbt/1.0/plugins
RUN touch $HOME/.sbt/1.0/plugins/build.sbt
RUN echo 'addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.1")' >> $HOME/.sbt/1.0/plugins/build.sbt
RUN curl -fLo $HOME/.vim/autoload/plug.vim --create-dirs https://raw.githubusercontent.com/junegunn/vim-plug/master/plug.vim
RUN mv $HOME/tmux.conf $HOME/.tmux.conf
RUN mv $HOME/vimrc $HOME/.vimrc
RUN mv $HOME/zshrc $HOME/.zshrc
RUN vim -c ":PlugInstall" -c ":qa"
RUN git clone https://github.com/mathieuancelin/heimdallr.git $HOME/heimdallr
RUN rm -f $HOME/Dockerfile
RUN rm -f $HOME/sbt-1.1.0.zip

EXPOSE 8081
EXPOSE 8443
EXPOSE 9081
EXPOSE 9443

CMD [""]
# docker run -p "8081:8081" -p "8443:8443" -p "9081:9081" -p "9443:9443" -it heimdallr-dev zsh
# docker run -it heimdallr-dev zsh